import { createClient, type SupabaseClient } from '@supabase/supabase-js'

const url = import.meta.env.VITE_SUPABASE_URL
const key = import.meta.env.VITE_SUPABASE_ANON_KEY

/**
 * Null when the build has no Supabase config — the app then says so instead of failing on every
 * request. The session lives in localStorage, which an installed iOS web app keeps separately
 * from Safari's.
 */
export const supabase: SupabaseClient | null = url && key
  ? createClient(url, key, { auth: { persistSession: true, autoRefreshToken: true, detectSessionInUrl: false } })
  : null
