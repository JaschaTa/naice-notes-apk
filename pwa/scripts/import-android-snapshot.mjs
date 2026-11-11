// One-off snapshot import: the Android app's Room rows → the user's personal space on Supabase,
// until Android syncs by itself (plan step 5). Re-running it updates the same rows rather than
// duplicating them, because ids are derived from the Android row ids (UUIDv5, namespace below).
// The Android 7→8 migration must backfill its uuid columns with the same derivation, so its
// sync adopts these rows instead of uploading copies. Never uploads the Claude section.
//
// Usage (from pwa/), with an adb backup of databases/naice-notes.db{,-wal,-shm} in <dir>:
//   sqlite3 -json <dir>/naice-notes.db "select * from sections where remoteKind is null order by position, createdAt" > <dir>/sections.json
//   sqlite3 -json <dir>/naice-notes.db "select i.* from items i join sections s on s.id=i.sectionId where s.remoteKind is null order by i.sectionId, i.position, i.createdAt" > <dir>/items.json
//   node scripts/import-android-snapshot.mjs <dir> <personal space id> <user id> <sort key of the first existing section, or "">
//   npx supabase db query --remote="$SUPABASE_PROJECT_REF" --yes -f <dir>/import.sql
// Caveat: a re-run overwrites edits made to those notes in the web app since the last import.
import { createHash } from 'node:crypto'
import { readFileSync, writeFileSync } from 'node:fs'
import { generateNKeysBetween } from 'fractional-indexing'
const [dir, spaceId, userId, beforeArg] = process.argv.slice(2)
const beforeKey = beforeArg || null
const NS = '6b1f8f0e-6a52-4e43-9d3a-5f0c2b9a7e11' // fixed namespace for Android row ids
function uuid5(name) {
  const h = createHash('sha1').update(Buffer.from(NS.replace(/-/g, ''), 'hex')).update(name).digest()
  h[6] = (h[6] & 0x0f) | 0x50; h[8] = (h[8] & 0x3f) | 0x80
  const x = h.subarray(0, 16).toString('hex')
  return `${x.slice(0, 8)}-${x.slice(8, 12)}-${x.slice(12, 16)}-${x.slice(16, 20)}-${x.slice(20)}`
}
const sections = JSON.parse(readFileSync(`${dir}/sections.json`, 'utf8'))
const items = JSON.parse(readFileSync(`${dir}/items.json`, 'utf8'))
const sKeys = generateNKeysBetween(null, beforeKey, sections.length)
const sRows = sections.map((s, i) => ({ id: uuid5(`section:${s.id}`), space_id: spaceId, name: s.name, color: s.color, emoji: s.emoji, sort_key: sKeys[i], created_at: s.createdAt }))
const iRows = []
for (const s of sections) {
  const mine = items.filter((it) => it.sectionId === s.id)
  const keys = generateNKeysBetween(null, null, mine.length)
  mine.forEach((it, i) => iRows.push({
    id: uuid5(`item:${it.id}`), section_id: uuid5(`section:${s.id}`), text: it.text, is_checked: !!it.isChecked,
    sort_key: keys[i], created_at: it.createdAt, created_by: userId, link_url: it.linkUrl, link_title: it.linkTitle,
    link_image_url: it.linkImageUrl, link_fetch_failed: !!it.linkFetchFailed, due_at: it.dueAt, repeat_weeks: it.repeatWeeks,
  }))
}
const tag = '$imp' + createHash('sha1').update(JSON.stringify(iRows)).digest('hex').slice(0, 8) + '$'
const sql = `
insert into public.sections (id, space_id, name, color, emoji, sort_key, created_at)
select id, space_id, name, color, emoji, sort_key, created_at
from jsonb_to_recordset(${tag}${JSON.stringify(sRows)}${tag}::jsonb)
  as t(id uuid, space_id uuid, name text, color int, emoji text, sort_key text, created_at bigint)
on conflict (id) do update set name = excluded.name, color = excluded.color, emoji = excluded.emoji,
  sort_key = excluded.sort_key, deleted_at = null;
insert into public.items (id, section_id, text, is_checked, sort_key, created_at, created_by, link_url, link_title,
  link_image_url, link_fetch_failed, due_at, repeat_weeks)
select * from jsonb_to_recordset(${tag}${JSON.stringify(iRows)}${tag}::jsonb)
  as t(id uuid, section_id uuid, text text, is_checked boolean, sort_key text, created_at bigint, created_by uuid,
       link_url text, link_title text, link_image_url text, link_fetch_failed boolean, due_at bigint, repeat_weeks int)
on conflict (id) do update set section_id = excluded.section_id, text = excluded.text, is_checked = excluded.is_checked,
  sort_key = excluded.sort_key, link_url = excluded.link_url, link_title = excluded.link_title,
  link_image_url = excluded.link_image_url, link_fetch_failed = excluded.link_fetch_failed,
  due_at = excluded.due_at, repeat_weeks = excluded.repeat_weeks, deleted_at = null;
select (select count(*) from public.sections where space_id = '${spaceId}' and deleted_at is null) as sections,
       (select count(*) from public.items i join public.sections s on s.id = i.section_id
        where s.space_id = '${spaceId}' and i.deleted_at is null) as items;
`
writeFileSync(`${dir}/import.sql`, sql)
console.log(`${sRows.length} sections, ${iRows.length} items`, sRows.map((s) => `${s.name}:${s.sort_key}`).join(' '))
