# Sync backend (Supabase)

Shared sections sync through a personal Supabase project. Everything server-side lives in
`migrations/`; RLS is the only security boundary, so the project URL and anon key are not
secrets (they still stay out of this repo — see `local.properties` and the Pages env vars).

## Test locally

No Supabase CLI or Docker needed — PGlite runs real Postgres in WASM, with a small stand-in for
Supabase's `auth` schema:

```bash
cd supabase/test && npm install && npm test        # VERBOSE=1 prints why each refusal failed
```

Expected-failure tests pass on *any* error, so after changing a policy or guard run with
`VERBOSE=1` and check each refusal fails for the intended reason, not a typo.

## Working with the live project

The CLI runs through `npx` (no install). It needs `npx supabase login` once — in a real terminal,
the browser flow fails without a TTY — plus `SUPABASE_PROJECT_REF` and `SUPABASE_DB_PASSWORD`
in the repo-root `.env` (git-ignored):

```bash
set -a && . ./.env && set +a
npx supabase db push --remote="$SUPABASE_PROJECT_REF" --dry-run      # then without --dry-run
npx supabase db query --remote="$SUPABASE_PROJECT_REF" --read-only "select …"
npx supabase db advisors --remote="$SUPABASE_PROJECT_REF"
npx supabase config diff --remote="$SUPABASE_PROJECT_REF"            # then config push
```

Hosted changes need `SUPABASE_YES=1` in the shell; the CLI ignores it in `.env` files. Read-only
queries run as `supabase_read_only_user`, which can't see triggers through `information_schema`
(use `pg_trigger`) or execute the auth hook.

**Never edit an applied migration.** Add a new file; `db push` only applies what's missing.

**Settings are in `config.toml`**, pulled from the project and pushed back with `config push`.
What's ours: `otp_length = 6`, Gmail SMTP, the two code templates, `email_sent = 30`, and `[auth.hook.before_user_created]` pointing at
`public.hook_before_user_created`. Everything else is Supabase's default.

**Advisors:** six "security definer function executable" warnings remain on purpose:
`create_shared_space`, `invite_to_space`, `share_section`, `unshare_section`, `leave_space`,
`space_people` are callable by signed-in users because that is their job; each checks the caller
itself. "Leaked password protection" doesn't apply — sign-in is by emailed code only. RLS helpers live in the `private` schema so they aren't exposed as RPCs.

## One-time project setup (done 2026-11-11)

1. Project in an EU region (Frankfurt), free tier.
2. `migrations/*.sql` applied with `db push`; settings with `config push`.
3. Email sign-up stays **on** — the hook does the gating; turning sign-ups off would block
   invited users too.
4. **Email goes out through Gmail** (app password "Naice Notes Supabase" on the owner's Google
   account; `SMTP_USER` / `SMTP_PASS` in `.env`, referenced as `env(…)` in `config.toml`). Custom
   SMTP is what makes the templates editable on the free tier — without it the API answers 400 and
   the default emails carry only a link. Both `confirmation` (first sign-in) and `magic_link`
   (later ones) use `templates/sign-in-code.html`, which shows `{{ .Token }}`: the apps sign in
   with the code, never the link, because on iOS a link opens Safari, not the installed PWA.
   Revoking the app password stops all login emails. Rate limit raised to 30 emails/hour.
5. Site URL = the PWA's live URL (Cloudflare Workers, see `pwa/wrangler.jsonc`). Local dev
   ports are allowed as additional redirect URLs.
6. First user (done): `insert into public.space_invites (email) values ('<email>');` Then sign in,
   create the household with `create_shared_space`, and invite the partner with `invite_to_space`.
   Note the account is created when the code is *requested*, not when it's entered.

## Invite email (Edge Function `send-invite`)

`functions/send-invite/index.ts` emails the person a section was just shared with. Both apps call
it right after `share_section` succeeds; it's best-effort, so a failed email never undoes a share.
It runs as the caller (their JWT, RLS applies) and only sends when the caller owns the section's
shared space **and** the address is already in it (member or open invite, via `space_people()`), so
it can't be used to mail arbitrary addresses. Mail goes through the same Gmail app password as the
sign-in codes, on port 465 (Edge Functions block 25 and 587).

```bash
set -a && . ./.env && set +a
npx supabase secrets set --remote="$SUPABASE_PROJECT_REF" SMTP_USER="$SMTP_USER" SMTP_PASS="$SMTP_PASS"   # once
npx supabase functions deploy send-invite --remote="$SUPABASE_PROJECT_REF" --use-api                    # no Docker needed
```

Without the secrets the function answers 502 and the apps carry on (the share still worked).

## Sync contract (for both clients)

- **Push:** upsert by client-generated `id` (`Prefer: resolution=merge-duplicates`). Never send
  `rev`, `updated_at` or `created_by` — triggers own them. Deleting = setting `deleted_at`.
- **Pull:** `rev > cursor - 200`, apply idempotently, cursor = max `rev` seen. The overlap exists
  because sequence values are handed out before commit, so a lower rev can become visible late.
- **Horizon:** if `cursor < sync_horizon.min_rev`, tombstones you never saw were purged — do a
  full resync for the space.
- **Spaces change too.** List `spaces` on every pull. A space not seen before has existing rows
  with old revs that a rev-based pull never returns — read it in full. A space that disappeared
  will never send another change — delete its rows locally. (`pwa/src/data/sync.ts` does both.)
- **Clients never move a row between spaces.** Items: the server rejects it. Sections: the server
  silently keeps its own `space_id`, so an upsert from a device that hasn't pulled a share yet
  still lands. Sections change space only through `share_section` / `unshare_section`, which
  bump revs so every device notices (unshare also bumps the section's items, because a device
  that drops the vanished shared space must pull them back in).
- **Sharing is per section.** Each shared section has its own shared space, owned by whoever
  shared it (`spaces.created_by`). Owner-only: tombstoning the section, adding sections to the
  space, inviting, unsharing. Members edit everything else and can `leave_space`. Server calls
  are online-only: sync before (push local edits) and after (pull the move).
  `space_people()` lists members with emails, plus open invites.
- **`sort_key` is `collate "C"`** — byte order, the same as Kotlin and JS string comparison.
  Postgres' default collation orders case differently and the clients would disagree.
- Times the clients own (`created_at`, `due_at`) are epoch millis, as on Android.
