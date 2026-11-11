// Sharing a section, stopping, leaving, and who's in it. Unlike note edits these are online-only
// server calls (share_section & co. in supabase/migrations): the rules — owner-only delete,
// invites — live in Postgres. Each call syncs before (so the server has every local edit) and
// after (so the moved section and its new space show up straight away).
import type { SupabaseClient } from '@supabase/supabase-js'
import type { Section } from './model.ts'
import type { Store } from './store.ts'
import type { SyncEngine } from './sync.ts'

export interface Person {
  spaceId: string
  email: string
  role: 'owner' | 'member'
  /** false: invited, hasn't signed in yet. */
  joined: boolean
}

/** The server calls, kept apart so the demo and tests can stand in for them. */
export interface SharingRemote {
  share(sectionId: string, email: string): Promise<string>
  unshare(sectionId: string): Promise<void>
  leave(spaceId: string): Promise<void>
  people(): Promise<Person[]>
}

export class SupabaseSharing implements SharingRemote {
  private sb: SupabaseClient
  constructor(sb: SupabaseClient) { this.sb = sb }

  async share(sectionId: string, email: string): Promise<string> {
    return this.rpc<string>('share_section', { p_section: sectionId, p_email: email })
  }
  async unshare(sectionId: string) { await this.rpc('unshare_section', { p_section: sectionId }) }
  async leave(spaceId: string) { await this.rpc('leave_space', { p_space: spaceId }) }
  async people(): Promise<Person[]> {
    const rows = await this.rpc<{ space_id: string; email: string; role: Person['role']; joined: boolean }[]>('space_people', {})
    return (rows ?? []).map((r) => ({ spaceId: r.space_id, email: r.email, role: r.role, joined: r.joined }))
  }

  private async rpc<T>(fn: string, args: object): Promise<T> {
    let result
    try { result = await this.sb.rpc(fn, args) } catch { throw new Error(OFFLINE) }
    if (result.error) throw new Error(friendly(result.error.message))
    return result.data as T
  }
}

const OFFLINE = 'You\'re offline. Sharing needs a connection.'

function friendly(message: string): string {
  if (/fetch|network/i.test(message)) return OFFLINE
  if (message.includes('not an email address')) return 'That doesn\'t look like an email address.'
  if (message.includes('you already have access')) return 'That\'s your own email address.'
  return message
}

export class Sharing {
  private remote: SharingRemote
  private store: Store
  private engine: SyncEngine
  constructor(remote: SharingRemote, store: Store, engine: SyncEngine) {
    this.remote = remote
    this.store = store
    this.engine = engine
  }

  people() { return this.remote.people() }

  async share(section: Section, email: string): Promise<void> {
    await this.engine.sync()
    await this.remote.share(section.id, email.trim())
    await this.engine.sync()
  }

  async unshare(section: Section): Promise<void> {
    await this.engine.sync()
    await this.remote.unshare(section.id)
    const personal = (await this.store.spaces()).find((s) => s.kind === 'personal')
    if (personal) await this.store.setSectionSpace(section.id, personal.id)
    await this.engine.sync()
  }

  async leave(section: Section): Promise<void> {
    await this.remote.leave(section.spaceId)
    await this.engine.sync() // the space is gone from the list, so the pull drops it locally
  }
}
