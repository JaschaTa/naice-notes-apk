// Local shapes (camelCase) and the server's row shapes (snake_case), plus the mapping between
// them. Times the clients own (createdAt, dueAt) are epoch millis, as on Android; deletedAt is
// an ISO string because the server stores it as timestamptz.

export interface Space {
  id: string
  name: string
  kind: 'personal' | 'shared'
  /** The owner: whoever shared the section in it (or, for a personal space, its user). */
  createdBy: string
}

/** Sync bookkeeping every local row carries. */
interface SyncState {
  /** Server rev of the copy we last saw; null until the row has reached the server once. */
  rev: number | null
  /** 1 while a local change hasn't been pushed. A number because IndexedDB can't index booleans. */
  dirty: 0 | 1
  /**
   * Bumped on every local write. A push only clears `dirty` if the version it sent is still
   * the current one — otherwise an edit made while the push was in flight would be lost.
   */
  version: number
}

export interface Section extends SyncState {
  id: string
  spaceId: string
  name: string
  color: number
  emoji: string | null
  sortKey: string
  createdAt: number
  deletedAt: string | null
}

export interface Item extends SyncState {
  id: string
  sectionId: string
  text: string
  isChecked: boolean
  sortKey: string
  createdAt: number
  createdBy: string | null
  linkUrl: string | null
  linkTitle: string | null
  linkImageUrl: string | null
  linkFetchFailed: boolean
  dueAt: number | null
  repeatWeeks: number | null
  deletedAt: string | null
}

export interface SectionRow {
  id: string
  space_id: string
  name: string
  color: number
  emoji: string | null
  sort_key: string
  created_at: number
  deleted_at: string | null
  rev?: number
}

export interface ItemRow {
  id: string
  section_id: string
  text: string
  is_checked: boolean
  sort_key: string
  created_at: number
  created_by?: string | null
  link_url: string | null
  link_title: string | null
  link_image_url: string | null
  link_fetch_failed: boolean
  due_at: number | null
  repeat_weeks: number | null
  deleted_at: string | null
  rev?: number
}

// bigint columns arrive as numbers from PostgREST; epoch millis fit comfortably in a double.

export function sectionToRow(s: Section): SectionRow {
  return {
    id: s.id, space_id: s.spaceId, name: s.name, color: s.color, emoji: s.emoji,
    sort_key: s.sortKey, created_at: s.createdAt, deleted_at: s.deletedAt,
  }
}

export function sectionFromRow(r: SectionRow): Section {
  return {
    id: r.id, spaceId: r.space_id, name: r.name, color: Number(r.color), emoji: r.emoji,
    sortKey: r.sort_key, createdAt: Number(r.created_at), deletedAt: r.deleted_at,
    rev: r.rev == null ? null : Number(r.rev), dirty: 0, version: 0,
  }
}

/** Never sends rev, updated_at or created_by — the server's triggers own those. */
export function itemToRow(i: Item): ItemRow {
  return {
    id: i.id, section_id: i.sectionId, text: i.text, is_checked: i.isChecked, sort_key: i.sortKey,
    created_at: i.createdAt, link_url: i.linkUrl, link_title: i.linkTitle,
    link_image_url: i.linkImageUrl, link_fetch_failed: i.linkFetchFailed, due_at: i.dueAt,
    repeat_weeks: i.repeatWeeks, deleted_at: i.deletedAt,
  }
}

export function itemFromRow(r: ItemRow): Item {
  return {
    id: r.id, sectionId: r.section_id, text: r.text, isChecked: r.is_checked, sortKey: r.sort_key,
    createdAt: Number(r.created_at), createdBy: r.created_by ?? null, linkUrl: r.link_url,
    linkTitle: r.link_title, linkImageUrl: r.link_image_url, linkFetchFailed: r.link_fetch_failed,
    dueAt: r.due_at == null ? null : Number(r.due_at), repeatWeeks: r.repeat_weeks,
    deletedAt: r.deleted_at, rev: r.rev == null ? null : Number(r.rev), dirty: 0, version: 0,
  }
}

/** Display order within a section or the rail: sort key bytewise, then age, then id. */
export function bySortKey<T extends { sortKey: string; createdAt: number; id: string }>(a: T, b: T): number {
  if (a.sortKey !== b.sortKey) return a.sortKey < b.sortKey ? -1 : 1
  if (a.createdAt !== b.createdAt) return a.createdAt - b.createdAt
  return a.id < b.id ? -1 : a.id > b.id ? 1 : 0
}
