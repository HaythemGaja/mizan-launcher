# Sreeb Remote — v1 protocol

The phone app (Sreeb Remote, `com.mizan.launcherremote`) controls
Sreeb on the TV (v3.19+, `com.mizan.launcher`) over the home
Wi-Fi only. There is no discovery, no account, no cloud, and no
other host: the phone talks to exactly one address — the one
the user typed, taken from the TV's own Settings › Phone remote
screen.

## Transport

- One endpoint: `POST http://<TV-IP>:47819/v1/command`
- Plain HTTP on the LAN (no TLS: a home network has no hostname
  to certify). The TV binds its site-local LAN address only,
  never 0.0.0.0, and never forwards a port — from outside the
  home Wi-Fi this endpoint is unreachable by construction.
- Body: one JSON object. Reply: one JSON object.
- The listener is OFF by default. Nothing listens until the
  user switches it on in Settings › Phone remote.

## Authentication

Every request carries the pairing code shown on the TV:

```json
{"pairing": "123456", "command": "status"}
```

- The code is exactly 6 digits. It is generated on the TV,
  shown in Settings › Phone remote, regenerable there, and
  Revoke stops the listener AND regenerates the code (old
  codes die immediately).
- There is no session and no "remember this phone": every
  request is authenticated on its own.
- After 5 wrong codes the TV refuses everything — even a
  correct code — for 60 seconds.
- No reply ever contains a secret: status reports counts and
  ids only. The arrow code, the TMDB key, and IPTV credentials
  are never sent, never logged, and never in backups.

## Commands (v1 — nothing more)

| command | params | effect |
| :- | :- | :- |
| `status` | — | Current state (see below). |
| `setStyle` | `style`: `"clean"` \| `"tv"` \| `"watch"` | Switches the home style. TV/Watch stores are untouched, so going back restores them exactly. |
| `setTheme` | `themeId`: one of the ids from `themeOptions` | Switches the Look & wallpaper theme preset. |
| `setAppLock` | `enabled`: boolean | Suspends (`false`) or restores (`true`) enforcement of per-app arrow locks. Membership is untouched. |
| `setFolderLock` | `enabled`: boolean | Same, for per-folder arrow locks. |
| `setArrowCode` | `arrowCode`: 4–8 letters from `UDLR` | Sets the arrow code (↑↓←→). Anything else is refused and the stored code is untouched. |
| `refresh` | — | Rebuilds the home now (or on its next build if it isn't visible). |

Every mutating command returns the fresh status on success,
so the phone re-renders from what the TV actually did.

## Replies

Success — `status` and every successful mutation:

```json
{
  "ok": true,
  "style": "clean",
  "themeId": "midnight",
  "themeOptions": [{"id": "apple_black", "name": "Apple Black"}, {"id": "midnight", "name": "Midnight"}],
  "appLock": true,
  "folderLock": true,
  "lockSet": true,
  "libraryTitles": 45
}
```

Failure — `ok` is false and nothing changed:

```json
{"ok": false, "error": "bad_code"}
```

Error codes: `bad_request` (malformed JSON), `missing_code`,
`bad_code` (wrong pairing code), `locked_out` (5 wrong codes —
wait 60 s), `bad_style`, `bad_theme`, `bad_value` (a lock toggle
that wasn't a boolean), `bad_code_value` (an arrow code that
isn't 4–8 UDLR letters), `unknown_command`, `not_found`
(wrong path — only `/v1/command` exists).

HTTP statuses mirror the JSON: 200 ok, 400 bad input,
401 bad/missing code, 429 locked out, 404 wrong path.

## Fail-open rules (TV side, core/RemoteProtocol)

- A rejected command is a no-op: no state changes, no crash.
- Bad input can never set a half-state: style, theme, toggles
  and arrow code are validated BEFORE they are written.
- If the TV process dies, the listener dies with it (it is a
  daemon thread, not a service). The phone then shows
  "unreachable" until the home restarts and re-arms the
  listener from prefs.

## What is NOT in v1 (deliberately)

No debloat/cleanup (that is the CleanYourTV phone app's job,
not this app's), no app launching from the phone, no library
editing, no settings beyond the commands above, no internet
at all.
