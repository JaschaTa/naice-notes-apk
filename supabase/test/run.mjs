// Runs the migrations against PGlite (real Postgres in WASM) with a minimal stand-in for
// Supabase's auth schema, then checks RLS, triggers and the invite flow.
// Usage: cd supabase/test && npm install && npm test
import { PGlite } from '@electric-sql/pglite'
import { readFileSync, readdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const here = dirname(fileURLToPath(import.meta.url))
const migrationsDir = join(here, '..', 'migrations')

const SUPABASE_STUB = `
  create role anon nologin;
  create role authenticated nologin;
  create role supabase_auth_admin nologin;
  create schema auth;
  create table auth.users (id uuid primary key default gen_random_uuid(), email text);
  create function auth.uid() returns uuid language sql stable as
    $$ select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid $$;
  grant usage on schema auth to anon, authenticated;
  grant execute on function auth.uid() to anon, authenticated;
  grant usage on schema public to anon, authenticated;
  create publication supabase_realtime;
`

const db = new PGlite()
await db.exec(SUPABASE_STUB)
for (const f of readdirSync(migrationsDir).filter((f) => f.endsWith('.sql')).sort()) {
  await db.exec(readFileSync(join(migrationsDir, f), 'utf8'))
}

let failures = 0
let passes = 0
const ok = (cond, name) => {
  if (cond) passes++
  else { failures++; console.error(`FAIL  ${name}`) }
}

/** Run as a signed-in user (or anon when uid is null), always resetting afterwards. */
async function as(uid, sql, params = []) {
  await db.exec(uid ? `set role authenticated; set request.jwt.claim.sub = '${uid}';`
                    : `set role anon; set request.jwt.claim.sub = '';`)
  try { return await db.query(sql, params) } finally { await db.exec('reset role; reset request.jwt.claim.sub;') }
}
async function fails(uid, sql, params, name) {
  try { await as(uid, sql, params); ok(false, `${name} (expected an error)`) }
  catch (e) { ok(true, name); if (process.env.VERBOSE) console.log(`  ${name}: ${e.message}`) }
}
const one = async (sql, params = []) => (await db.query(sql, params)).rows[0]
const hook = async (email) => (await one(
  `select public.hook_before_user_created($1::jsonb) as r`, [JSON.stringify({ user: { email } })])).r
const createUser = async (email) => (await one(
  `insert into auth.users (email) values ($1) returning id`, [email])).id
const uuid = async () => (await one('select gen_random_uuid() as id')).id
const now = Date.now()

// ── Invite allowlist ──
ok((await hook('a@example.com')).error?.http_code === 403, 'uninvited email is refused')
await db.query(`insert into public.space_invites (email) values ('a@example.com')`)
ok(Object.keys(await hook('A@Example.com')).length === 0, 'bootstrap invite admits, case-insensitively')

// ── First user ──
const A = await createUser('A@example.com')
const aSpaces = (await as(A, `select kind from public.spaces`)).rows
ok(aSpaces.length === 1 && aSpaces[0].kind === 'personal', 'first login creates one personal space')
ok((await one(`select accepted_at from public.space_invites where email='a@example.com'`)).accepted_at,
  'bootstrap invite marked accepted')
ok((await hook('a@example.com')).error, 'an accepted invite no longer admits')

const H = (await as(A, `select public.create_shared_space('Household') as id`)).rows[0].id
const aPersonal = (await as(A, `select id from public.spaces where kind='personal'`)).rows[0].id

// ── Inviting the partner ──
await as(A, `select public.invite_to_space($1, ' B@Example.com ')`, [H])
ok(Object.keys(await hook('b@example.com')).length === 0, 'invited partner is admitted')
const B = await createUser('b@example.com')
const bSpaces = (await as(B, `select kind from public.spaces order by kind`)).rows.map((r) => r.kind)
ok(bSpaces.join() === 'personal,shared', 'partner lands in household + own personal space')
ok((await as(B, `select * from public.space_members where space_id=$1`, [H])).rows.length === 2,
  'partner sees both household members')

// Outsider: created directly, bypassing the hook, to test RLS in isolation.
const C = await createUser('c@example.com')
await fails(C, `select public.invite_to_space($1, 'x@example.com')`, [H], 'outsider cannot invite into household')
await fails(A, `select public.invite_to_space($1, 'x@example.com')`, [aPersonal], 'personal space cannot be shared')

// Inviting someone who already has an account joins them immediately.
await as(A, `select public.invite_to_space($1, 'c@example.com')`, [H])
ok((await as(C, `select 1 from public.spaces where id=$1`, [H])).rows.length === 1, 'existing user joins on invite')
await db.query(`delete from public.space_members where space_id=$1 and user_id=$2`, [H, C])

// ── Content & RLS ──
const sH = await uuid(), sP = await uuid(), sH2 = await uuid()
await as(A, `insert into public.sections (id, space_id, name, color, sort_key, created_at)
             values ($1,$2,'Groceries',-1,'a0',$4), ($3,$5,'Private',-1,'a0',$4), ($6,$2,'Household',-1,'a1',$4)`,
  [sH, H, sP, now, aPersonal, sH2])
const i1 = await uuid()
await as(A, `insert into public.items (id, section_id, text, sort_key, created_at, rev)
             values ($1,$2,'Milk','a0',$3, 1)`, [i1, sH, now])

const bSections = (await as(B, `select name from public.sections order by name`)).rows.map((r) => r.name)
ok(bSections.join() === 'Groceries,Household', 'partner sees shared sections, not A\'s private one')
ok((await as(B, `select text from public.items`)).rows[0]?.text === 'Milk', 'partner sees shared items')
ok((await as(C, `select * from public.sections`)).rows.length === 0, 'outsider sees no sections')
ok((await as(C, `select * from public.items`)).rows.length === 0, 'outsider sees no items')
await fails(null, `select * from public.items`, [], 'anon cannot read items')
await fails(C, `insert into public.items (id, section_id, text, sort_key, created_at) values ($1,$2,'x','a',0)`,
  [await uuid(), sH], 'outsider cannot insert into household section')

// Outsider upserting over an existing id must not take it over.
await fails(C, `insert into public.sections (id, space_id, name, color, sort_key, created_at)
                values ($1, (select id from public.spaces where kind='personal'), 'Hijack', 0, 'a', 0)
                on conflict (id) do update set name = excluded.name`, [sH], 'outsider cannot upsert over a foreign row')

const item = await one(`select rev, created_by from public.items where id=$1`, [i1])
ok(item.created_by === A, 'created_by defaults to the caller')
ok(Number(item.rev) > 1, 'client-supplied rev is overwritten')

await as(B, `update public.items set is_checked = true, created_by = $2 where id = $1`, [i1, B])
const after = await one(`select rev, created_by, is_checked from public.items where id=$1`, [i1])
ok(after.is_checked && Number(after.rev) > Number(item.rev), 'partner edit lands and bumps rev')
ok(after.created_by === A, 'created_by cannot be rewritten')

await fails(B, `delete from public.items where id=$1`, [i1], 'hard delete is not allowed')

// ── Space boundaries ──
await as(A, `update public.sections set space_id=$2, name='Groceries' where id=$1`, [sH, aPersonal])
ok((await one(`select space_id from public.sections where id=$1`, [sH])).space_id === H,
  'a client write cannot change a section\'s space (server keeps its own)')
await fails(A, `update public.items set section_id=$2 where id=$1`, [i1, sP], 'item cannot move to another space')
await as(A, `update public.items set section_id=$2 where id=$1`, [i1, sH2])
ok((await one(`select section_id from public.items where id=$1`, [i1])).section_id === sH2,
  'item can move within the same space')

// ── Tombstones ──
await as(A, `update public.sections set deleted_at = now() where id=$1`, [sH2])
ok((await one(`select deleted_at from public.items where id=$1`, [i1])).deleted_at,
  'tombstoning a section tombstones its items')
await as(A, `insert into public.items (id, section_id, text, sort_key, created_at) values ($1,$2,'Milk','a0',$3)
             on conflict (id) do update set deleted_at = null, section_id = excluded.section_id`, [i1, sH, now])
ok((await one(`select deleted_at from public.items where id=$1`, [i1])).deleted_at === null,
  'upsert revives a tombstoned item')

// ── Ordering: byte order, matching Kotlin/JS string comparison ──
for (const k of ['a', 'B', 'Zz', 'a0']) {
  await as(A, `insert into public.items (id, section_id, text, sort_key, created_at) values ($1,$2,$3,$3,0)`,
    [await uuid(), sH, k])
}
const keys = (await as(A, `select sort_key from public.items where section_id=$1 and text <> 'Milk' order by sort_key`, [sH]))
  .rows.map((r) => r.sort_key)
ok(keys.join() === ['a', 'B', 'Zz', 'a0'].sort().join(), `sort_key orders bytewise (got ${keys})`)

// ── Helpers are not part of the API ──
await fails(B, `select public.section_space($1)`, [sH], 'section_space is not callable from public')
await fails(B, `select public.is_member($1)`, [H], 'is_member is not callable from public')

// ── Renaming spaces: owner only ──
await as(B, `update public.spaces set name = 'Ours' where id = $1`, [H])
ok((await one(`select name from public.spaces where id=$1`, [H])).name === 'Household', 'member cannot rename a space')
await as(A, `update public.spaces set name = 'Home' where id = $1`, [H])
ok((await one(`select name from public.spaces where id=$1`, [H])).name === 'Home', 'owner can rename a space')

// ── Per-section sharing ──
// D is new: invited only through share_section. B already has an account.
const sG = await uuid()
await as(A, `insert into public.sections (id, space_id, name, color, sort_key, created_at)
             values ($1,$2,'Garden',-1,'b0',$3)`, [sG, aPersonal, now])
const gItem = await uuid()
await as(A, `insert into public.items (id, section_id, text, sort_key, created_at) values ($1,$2,'Seeds','a0',$3)`,
  [gItem, sG, now])
const gRev0 = Number((await one(`select rev from public.items where id=$1`, [gItem])).rev)

await fails(B, `select public.share_section($1, 'd@example.com')`, [sG], 'cannot share someone else\'s section')
await fails(A, `select public.share_section($1, 'a@example.com')`, [sG], 'cannot share with yourself')
await fails(A, `select public.share_section($1, 'not-an-email')`, [sG], 'share needs a real email')
ok((await one(`select space_id from public.sections where id=$1`, [sG])).space_id === aPersonal,
  'a refused share leaves the section where it was')

const G = (await as(A, `select public.share_section($1, 'b@example.com') as s`, [sG])).rows[0].s
ok(G !== aPersonal && (await one(`select kind from public.spaces where id=$1`, [G])).kind === 'shared',
  'sharing moves the section into a new shared space, same id')
ok((await as(B, `select name from public.sections where id=$1`, [sG])).rows[0]?.name === 'Garden',
  'existing user sees the shared section at once')
ok((await as(B, `select text from public.items where section_id=$1`, [sG])).rows[0]?.text === 'Seeds',
  'and its notes')
ok((await as(C, `select 1 from public.sections where id=$1`, [sG])).rows.length === 0, 'outsider still sees nothing')

await as(A, `select public.share_section($1, 'D@example.com')`, [sG])
ok(Object.keys(await hook('d@example.com')).length === 0, 'sharing with a new email admits them')
const people = (await as(B, `select email, role, joined from public.space_people() where space_id=$1 order by email`, [G])).rows
ok(people.map((p) => `${p.email}:${p.role}:${p.joined}`).join() ===
  'a@example.com:owner:true,b@example.com:member:true,d@example.com:member:false',
  `space_people lists owner, members and open invites (got ${people.map((p) => p.email)})`)
ok((await as(C, `select * from public.space_people()`)).rows.length === 0, 'outsider sees no people')

// Member rights: edit yes; delete, add sections, invite no.
// Clients push edits as upserts, carrying every column — including a stale space_id.
await as(B, `insert into public.sections (id, space_id, name, color, sort_key, created_at)
             values ($1,$2,'Garden 🌱',-1,'b0',0)
             on conflict (id) do update set name = excluded.name, space_id = excluded.space_id`, [sG, aPersonal])
const renamed = await one(`select name, space_id from public.sections where id=$1`, [sG])
ok(renamed.name === 'Garden 🌱', 'member can rename through an upsert')
ok(renamed.space_id === G, 'a stale space_id in the upsert is ignored')
await as(B, `insert into public.items (id, section_id, text, sort_key, created_at) values ($1,$2,'Water','Zz',$3)`,
  [await uuid(), sG, now])
ok((await as(A, `select 1 from public.items where section_id=$1 and text='Water'`, [sG])).rows.length === 1,
  'member can add notes')
await fails(B, `update public.sections set deleted_at=now() where id=$1`, [sG], 'member cannot delete a shared section')
await fails(B, `insert into public.sections (id, space_id, name, color, sort_key, created_at)
                values ($1,$2,'Mine',-1,'c0',0) on conflict (id) do update set deleted_at = now()`,
  [sG, G], 'member cannot delete it through an upsert either')
await fails(B, `insert into public.sections (id, space_id, name, color, sort_key, created_at)
                values ($1,$2,'Extra',-1,'c0',0)`, [await uuid(), G], 'member cannot add sections to the space')
await fails(B, `select public.invite_to_space($1, 'x@example.com')`, [G], 'member cannot invite')
await fails(B, `select public.unshare_section($1)`, [sG], 'member cannot unshare')
await fails(A, `select public.leave_space($1)`, [G], 'owner cannot leave (unshares instead)')

// Leaving.
await as(B, `select public.leave_space($1)`, [G])
ok((await as(B, `select 1 from public.sections where id=$1`, [sG])).rows.length === 0, 'after leaving, the section is gone for B')
ok((await as(A, `select 1 from public.space_people() where email='b@example.com' and space_id=$1`, [G])).rows.length === 0,
  'and B is gone from the people list')
ok((await one(`select name from public.sections where id=$1`, [sG])).name === 'Garden 🌱', 'the owner keeps it')
await fails(B, `select public.leave_space($1)`, [G], 'cannot leave twice')

// Unsharing.
await as(A, `select public.share_section($1, 'b@example.com')`, [sG])
await as(A, `select public.unshare_section($1)`, [sG])
ok((await one(`select space_id from public.sections where id=$1`, [sG])).space_id === aPersonal,
  'unsharing moves the section back to the personal space')
ok(!(await one(`select 1 as x from public.spaces where id=$1`, [G])), 'and removes the shared space')
ok((await as(B, `select 1 from public.sections where id=$1`, [sG])).rows.length === 0, 'B loses access')
ok((await hook('d@example.com')).error, 'the open invite for D is gone too')
ok(Number((await one(`select rev from public.items where id=$1`, [gItem])).rev) > gRev0,
  'unsharing bumps item revs so devices pull them again')

// Owner deletes a shared section: gone for everyone.
const G2 = (await as(A, `select public.share_section($1, 'b@example.com') as s`, [sG])).rows[0].s
await as(A, `update public.sections set deleted_at = now() where id=$1`, [sG])
ok((await as(B, `select deleted_at from public.sections where id=$1`, [sG])).rows[0]?.deleted_at,
  'owner can delete a shared section; B receives the tombstone')
ok(G2, 'second share created a fresh space')

// ── Purge & horizon ──
await fails(A, `select public.purge_tombstones()`, [], 'clients cannot purge')
await db.query(`update public.items set deleted_at = now() - interval '40 days' where id=$1`, [i1])
const purgedRev = (await one(`select rev from public.items where id=$1`, [i1])).rev
await db.query(`select public.purge_tombstones()`)
ok(!(await one(`select 1 as x from public.items where id=$1`, [i1])), 'old tombstone purged')
ok(Number((await as(A, `select min_rev from public.sync_horizon`)).rows[0].min_rev) === Number(purgedRev),
  'horizon raised to the purged rev')

console.log(`${passes} passed, ${failures} failed`)
process.exit(failures ? 1 : 0)
