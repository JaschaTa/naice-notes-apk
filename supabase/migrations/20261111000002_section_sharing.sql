-- Sharing is per section: each shared section gets a shared space of its own, owned by whoever
-- shared it. That makes "leave" and "stop sharing" one membership/space operation each, and keeps
-- the rest of the model (RLS by space, sync by rev) untouched.
--
-- Rules, enforced here rather than trusted to the apps:
--   * Only the owner deletes (tombstones) a section. A member can only leave.
--   * Only the owner adds sections to a space or invites people into it.
--   * A section changes space only through share_section / unshare_section. A client upsert
--     carrying a stale space_id is not an error: the server keeps its own space_id, so an edit
--     made on a device that hasn't heard about the share yet still lands.
--   * unshare_section bumps the rev of every item it moves back, so a device that already
--     forgot the shared space (removed spaces are dropped locally) pulls them in again.

create function private.is_owner(p_space uuid)
returns boolean
language sql stable security definer set search_path = ''
as $$
  select exists (
    select 1 from public.space_members m
    where m.space_id = p_space and m.user_id = auth.uid() and m.role = 'owner'
  );
$$;

grant execute on function private.is_owner(uuid) to authenticated;

-- Runs as the invoker: `authenticated` for a client's own write, the function owner when the
-- write comes from share_section / unshare_section.
create or replace function public.guard_section_update()
returns trigger
language plpgsql set search_path = ''
as $$
begin
  if new.space_id is distinct from old.space_id and current_user in ('authenticated', 'anon') then
    new.space_id := old.space_id;
  end if;
  if new.deleted_at is not null and old.deleted_at is null and current_user in ('authenticated', 'anon')
     and not private.is_owner(new.space_id) then
    raise exception 'only the owner can delete a shared section; leave it instead'
      using errcode = 'insufficient_privilege';
  end if;
  return new;
end;
$$;

-- Not an RLS policy: INSERT policies also apply to an upsert that ends up updating, and clients
-- push every section edit as an upsert, so a policy would lock members out of renaming. A
-- BEFORE INSERT trigger fires for upserts too, hence the check for an existing row. For an
-- existing row it also swaps in the server's space_id, so the insert policy judges the space the
-- row is really in, not the stale one a device may still send.
create function public.guard_section_insert()
returns trigger
language plpgsql set search_path = ''
as $$
declare
  v_space uuid;
begin
  if current_user not in ('authenticated', 'anon') then
    return new;
  end if;
  select s.space_id into v_space from public.sections s where s.id = new.id;  -- RLS: only visible rows
  if found then
    new.space_id := v_space;
  elsif not private.is_owner(new.space_id) then
    raise exception 'only the owner can add sections to a shared space'
      using errcode = 'insufficient_privilege';
  end if;
  return new;
end;
$$;

create trigger sections_guard_insert before insert on public.sections
  for each row execute function public.guard_section_insert();

-- Owner only now: a member can't pass the section on to someone else.
create or replace function public.invite_to_space(p_space uuid, p_email text)
returns void
language plpgsql security definer set search_path = ''
as $$
declare
  v_email text := lower(trim(p_email));
  v_user  uuid;
begin
  if not private.is_owner(p_space) then
    raise exception 'only the owner can invite' using errcode = 'insufficient_privilege';
  end if;
  if (select kind from public.spaces where id = p_space) <> 'shared' then
    raise exception 'personal spaces cannot be shared' using errcode = 'check_violation';
  end if;
  if v_email !~ '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$' then
    raise exception 'not an email address' using errcode = 'check_violation';
  end if;
  if v_email = (select lower(email) from auth.users where id = auth.uid()) then
    raise exception 'you already have access' using errcode = 'check_violation';
  end if;

  select id into v_user from auth.users where lower(email) = v_email;

  insert into public.space_invites (space_id, email, invited_by, accepted_at)
  values (p_space, v_email, auth.uid(), case when v_user is not null then now() end)
  on conflict (space_id, email) do nothing;

  if v_user is not null then
    insert into public.space_members (space_id, user_id, role)
    values (p_space, v_user, 'member')
    on conflict do nothing;
  end if;
end;
$$;

-- Shares one of your sections with p_email. The first time, the section moves out of your
-- personal space into a new shared space; after that it only adds people. Returns the space.
create function public.share_section(p_section uuid, p_email text)
returns uuid
language plpgsql security definer set search_path = ''
as $$
declare
  v_space uuid;
  v_kind  text;
  v_name  text;
begin
  select s.space_id, sp.kind, s.name into v_space, v_kind, v_name
  from public.sections s join public.spaces sp on sp.id = s.space_id
  where s.id = p_section and s.deleted_at is null;
  if not found or not private.is_owner(v_space) then
    raise exception 'section not found, or not yours to share' using errcode = 'insufficient_privilege';
  end if;

  if v_kind = 'personal' then
    insert into public.spaces (name, kind, created_by) values (v_name, 'shared', auth.uid())
    returning id into v_space;
    insert into public.space_members (space_id, user_id, role) values (v_space, auth.uid(), 'owner');
    update public.sections set space_id = v_space where id = p_section;
  end if;

  perform public.invite_to_space(v_space, p_email);  -- raises (and rolls all of this back) on a bad email
  return v_space;
end;
$$;

-- Makes a shared section private again: it moves back to your personal space and the shared
-- space goes, taking everyone else's access (and open invites) with it.
create function public.unshare_section(p_section uuid)
returns void
language plpgsql security definer set search_path = ''
as $$
declare
  v_space    uuid;
  v_personal uuid;
begin
  select s.space_id into v_space
  from public.sections s join public.spaces sp on sp.id = s.space_id
  where s.id = p_section and sp.kind = 'shared';
  if not found or not private.is_owner(v_space) then
    raise exception 'section not found, or not yours to unshare' using errcode = 'insufficient_privilege';
  end if;

  select id into v_personal from public.spaces where created_by = auth.uid() and kind = 'personal';

  update public.sections set space_id = v_personal where space_id = v_space and deleted_at is null;
  update public.items set updated_at = now()  -- new revs; see the header
  where deleted_at is null
    and section_id in (select id from public.sections where space_id = v_personal);
  delete from public.spaces where id = v_space;  -- cascades: memberships, invites, old tombstones
end;
$$;

-- A member removes themselves. The owner can't leave; they unshare instead.
create function public.leave_space(p_space uuid)
returns void
language plpgsql security definer set search_path = ''
as $$
begin
  delete from public.space_members
  where space_id = p_space and user_id = auth.uid() and role = 'member';
  if not found then
    raise exception 'not a member of this space' using errcode = 'insufficient_privilege';
  end if;
  delete from public.space_invites
  where space_id = p_space and email = (select lower(email) from auth.users where id = auth.uid());
end;
$$;

-- Who else is in your shared spaces, with emails (space_members only has user ids, and
-- auth.users isn't readable). Open invites are listed as not yet joined.
create function public.space_people()
returns table (space_id uuid, email text, role text, joined boolean)
language sql stable security definer set search_path = ''
as $$
  select m.space_id, lower(u.email), m.role, true
  from public.space_members m
  join public.spaces sp on sp.id = m.space_id and sp.kind = 'shared'
  join auth.users u on u.id = m.user_id
  where private.is_member(m.space_id)
  union all
  select i.space_id, i.email, 'member', false
  from public.space_invites i
  where i.accepted_at is null and i.space_id is not null and private.is_member(i.space_id);
$$;

revoke execute on function public.share_section(uuid, text), public.unshare_section(uuid),
                           public.leave_space(uuid), public.space_people() from public, anon;
grant execute on function public.share_section(uuid, text), public.unshare_section(uuid),
                          public.leave_space(uuid), public.space_people() to authenticated;
