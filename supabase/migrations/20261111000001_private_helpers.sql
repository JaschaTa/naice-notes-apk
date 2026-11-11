-- Advisor follow-ups on the initial schema.
--
-- 1. RLS helpers out of the exposed API. is_member / section_space are security definer so
--    policies don't recurse through space_members' own RLS, but living in `public` also made them
--    callable as /rest/v1/rpc/*. section_space would tell any signed-in user which space a
--    section belongs to, given its id. `private` is not exposed by PostgREST; policies keep
--    working because they reference functions by OID, and `authenticated` keeps EXECUTE because
--    policies run as the caller.
--    PL/pgSQL bodies resolve names at run time, though, so every function that called them by
--    name is re-created below.
-- 2. auth.uid() wrapped in (select …) in the two policies that called it per row.

create schema if not exists private;
revoke all on schema private from public;
grant usage on schema private to authenticated;

alter function public.is_member(uuid) set schema private;
alter function public.section_space(uuid) set schema private;

create or replace function public.guard_item_write()
returns trigger
language plpgsql set search_path = ''
as $$
begin
  if tg_op = 'INSERT' then
    new.created_by := coalesce(new.created_by, auth.uid());
  else
    new.created_by := old.created_by;  -- authorship is not editable
    if new.section_id is distinct from old.section_id
       and private.section_space(new.section_id) is distinct from private.section_space(old.section_id) then
      raise exception 'items cannot move between spaces; tombstone and re-create instead'
        using errcode = 'check_violation';
    end if;
  end if;
  return new;
end;
$$;

create or replace function public.invite_to_space(p_space uuid, p_email text)
returns void
language plpgsql security definer set search_path = ''
as $$
declare
  v_email text := lower(trim(p_email));
  v_user  uuid;
begin
  if not private.is_member(p_space) then
    raise exception 'not a member of this space' using errcode = 'insufficient_privilege';
  end if;
  if (select kind from public.spaces where id = p_space) <> 'shared' then
    raise exception 'personal spaces cannot be shared' using errcode = 'check_violation';
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

drop policy spaces_rename on public.spaces;
create policy spaces_rename on public.spaces for update to authenticated
  using (exists (select 1 from public.space_members m
                 where m.space_id = spaces.id and m.user_id = (select auth.uid()) and m.role = 'owner'));

drop policy invites_read on public.space_invites;
create policy invites_read on public.space_invites for select to authenticated
  using (invited_by = (select auth.uid()) or (space_id is not null and private.is_member(space_id)));
