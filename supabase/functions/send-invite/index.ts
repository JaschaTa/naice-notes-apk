// Emails the person a section was just shared with. Called by both apps right after
// share_section succeeds; best-effort — the share stands whether or not this lands.
//
// Not an open mailer: it runs as the caller (their JWT, so RLS applies) and only sends when
//   * the caller owns the shared space the section is in, and
//   * the address is already in that space — a member or an open invite (space_people()).
// So it can only ever email someone the caller has just been allowed to share with.
//
// Mail goes out through the same Gmail account as the sign-in codes (secrets SMTP_USER /
// SMTP_PASS — see supabase/README.md). Port 465: Edge Functions block 25 and 587.
import { createClient } from 'npm:@supabase/supabase-js@2'
import nodemailer from 'npm:nodemailer@6'

const SITE = 'https://naice-notes.com'

const cors = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
}

function reply(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { ...cors, 'Content-Type': 'application/json' } })
}

function escape(s: string) {
  return s.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]!)
}

Deno.serve(async (req) => {
  if (req.method === 'OPTIONS') return new Response('ok', { headers: cors })
  if (req.method !== 'POST') return reply(405, { error: 'POST only' })

  const authorization = req.headers.get('Authorization') ?? ''
  const sb = createClient(Deno.env.get('SUPABASE_URL')!, Deno.env.get('SUPABASE_ANON_KEY')!, {
    global: { headers: { Authorization: authorization } },
    auth: { persistSession: false },
  })
  const { data: { user } } = await sb.auth.getUser(authorization.replace(/^Bearer\s+/i, ''))
  if (!user) return reply(401, { error: 'not signed in' })

  let sectionId: string, to: string
  try {
    const body = await req.json()
    sectionId = String(body.section_id ?? '')
    to = String(body.email ?? '').trim().toLowerCase()
  } catch {
    return reply(400, { error: 'bad request' })
  }
  if (!sectionId || !to) return reply(400, { error: 'section_id and email are required' })
  if (to === user.email?.toLowerCase()) return reply(400, { error: 'that is your own address' })

  // All three reads run as the caller, so they only see what RLS lets them see.
  const { data: section } = await sb.from('sections').select('name, space_id').eq('id', sectionId).maybeSingle()
  if (!section) return reply(404, { error: 'section not found' })
  const { data: space } = await sb.from('spaces').select('kind, created_by').eq('id', section.space_id).maybeSingle()
  if (!space || space.kind !== 'shared' || space.created_by !== user.id) return reply(403, { error: 'not your shared section' })
  const { data: people, error: peopleError } = await sb.rpc('space_people')
  if (peopleError) return reply(500, { error: peopleError.message })
  const person = (people as { space_id: string; email: string; joined: boolean }[])
    .find((p) => p.space_id === section.space_id && p.email === to)
  if (!person) return reply(403, { error: 'that address is not in this section' })

  const from = user.email ?? 'Someone'
  const name = section.name
  const subject = `${from} shared "${name}" with you`
  const steps = person.joined
    ? `You will find it in your sections: ${SITE}`
    : `Sign up with your email address to check the notes: ${SITE}`
  const text = `Hey there 👋\n\n${from} shared the section "${name}" with you in Naice Notes.\n\n${steps}\n\nOn an iPhone: open it in Safari, then Share → Add to Home Screen to keep it like an app.`
  const html = `<div style="font-family:-apple-system,Segoe UI,Roboto,sans-serif;font-size:16px;line-height:1.5;color:#1b1b1f;max-width:480px">
<p>Hey there 👋</p>
<p><b>${escape(from)}</b> shared the section <b>“${escape(name)}”</b> with you in Naice Notes.</p>
<p>${person.joined
    ? `You will find it in your sections.`
    : `Sign up with your email address to check the notes.`}</p>
<p><a href="${SITE}" style="display:inline-block;background:#1b1b1f;color:#fff;text-decoration:none;padding:10px 18px;border-radius:10px">Open Naice Notes</a></p>
<p style="color:#666;font-size:14px">On an iPhone: open it in Safari, then Share → Add to Home Screen to keep it like an app.</p>
</div>`

  try {
    const transport = nodemailer.createTransport({
      host: 'smtp.gmail.com',
      port: 465,
      secure: true,
      auth: { user: Deno.env.get('SMTP_USER')!, pass: Deno.env.get('SMTP_PASS')! },
    })
    await transport.sendMail({
      from: `"Naice Notes" <${Deno.env.get('SMTP_USER')}>`,
      to,
      replyTo: from,
      subject,
      text,
      html,
    })
  } catch (e) {
    console.error('send-invite: mail failed', e instanceof Error ? e.message : e)
    return reply(502, { error: 'could not send the email' })
  }
  return reply(200, { sent: true })
})
