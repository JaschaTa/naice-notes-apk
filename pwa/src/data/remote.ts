// What the sync engine needs from the server, and the Supabase implementation of it. Kept as an
// interface so the engine can be tested against an in-memory fake (see sync.test.ts).
import type { SupabaseClient } from '@supabase/supabase-js'
import type { ItemRow, SectionRow, Space } from './model.ts'

export interface Remote {
  listSpaces(): Promise<Space[]>
  /** Lowest cursor still safe to resume from; below it, purged tombstones may have been missed. */
  horizon(): Promise<number>
  /** Rows with rev > since, oldest first. */
  sectionsSince(since: number): Promise<SectionRow[]>
  itemsSince(since: number): Promise<ItemRow[]>
  /** Everything in the given spaces, for spaces this device hasn't seen before. */
  sectionsInSpaces(spaceIds: string[]): Promise<SectionRow[]>
  itemsInSpaces(spaceIds: string[]): Promise<ItemRow[]>
  upsertSections(rows: SectionRow[]): Promise<void>
  upsertItems(rows: ItemRow[]): Promise<void>
}

/**
 * `permanent` means retrying the same request can't succeed (the server refused the row);
 * otherwise it's the network or an expired session, and the outbox simply waits.
 */
export class RemoteError extends Error {
  readonly permanent: boolean
  constructor(message: string, permanent: boolean) {
    super(message)
    this.permanent = permanent
  }
}

const PAGE = 1000 // PostgREST's default max rows

interface PgError { message: string; code?: string }

function toRemoteError(e: PgError): RemoteError {
  // A Postgres or PostgREST error code means the server looked at the request and said no.
  // PGRST3xx are JWT problems — the session refreshes itself, so those are worth retrying.
  const code = e.code ?? ''
  const permanent = code !== '' && !code.startsWith('PGRST3')
  return new RemoteError(`${code || 'network'}: ${e.message}`, permanent)
}

export class SupabaseRemote implements Remote {
  private sb: SupabaseClient
  constructor(sb: SupabaseClient) { this.sb = sb }

  async listSpaces(): Promise<Space[]> {
    const { data, error } = await this.sb.from('spaces').select('id, name, kind')
    if (error) throw toRemoteError(error)
    return data as Space[]
  }

  async horizon(): Promise<number> {
    const { data, error } = await this.sb.from('sync_horizon').select('min_rev').maybeSingle()
    if (error) throw toRemoteError(error)
    return Number(data?.min_rev ?? 0)
  }

  sectionsSince(since: number) { return this.pageByRev<SectionRow>('sections', '*', since) }
  itemsSince(since: number) { return this.pageByRev<ItemRow>('items', '*', since) }

  async sectionsInSpaces(spaceIds: string[]): Promise<SectionRow[]> {
    if (spaceIds.length === 0) return []
    return this.pageByRev<SectionRow>('sections', '*', 0, (q) => q.in('space_id', spaceIds))
  }

  async itemsInSpaces(spaceIds: string[]): Promise<ItemRow[]> {
    if (spaceIds.length === 0) return []
    const rows = await this.pageByRev<ItemRow & { sections?: unknown }>(
      'items', '*, sections!inner(space_id)', 0, (q) => q.in('sections.space_id', spaceIds))
    return rows.map(({ sections: _embedded, ...row }) => row)
  }

  async upsertSections(rows: SectionRow[]) { await this.upsert('sections', rows) }
  async upsertItems(rows: ItemRow[]) { await this.upsert('items', rows) }

  private async upsert(table: string, rows: object[]) {
    if (rows.length === 0) return
    const { error } = await this.sb.from(table).upsert(rows, { onConflict: 'id' })
    if (error) throw toRemoteError(error)
  }

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  private async pageByRev<T extends { rev?: number }>(table: string, columns: string, since: number, filter?: (q: any) => any): Promise<T[]> {
    const out: T[] = []
    let cursor = since
    for (;;) {
      let q = this.sb.from(table).select(columns).gt('rev', cursor).order('rev').limit(PAGE)
      if (filter) q = filter(q)
      const { data, error } = await q
      if (error) throw toRemoteError(error)
      const page = (data ?? []) as unknown as T[]
      out.push(...page)
      if (page.length < PAGE) return out
      cursor = Number(page[page.length - 1].rev)
    }
  }
}

/** fetch() rejects with a TypeError when offline — the supabase client surfaces it like this. */
export function isOffline(e: unknown): boolean {
  return e instanceof RemoteError ? !e.permanent : e instanceof TypeError
}
