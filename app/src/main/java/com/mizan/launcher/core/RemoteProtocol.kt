package com.mizan.launcher.core

/**
 * Phone-remote v1 protocol (v3.19) — pure decisions, no Android
 * imports, so the headless sim verifies the whole command
 * surface without a socket.
 *
 * TRANSPORT (implemented by data/RemoteServer, documented in
 * phone-remote/PROTOCOL.md): one endpoint, POST /v1/command,
 * JSON body {"pairing": "<6 digits>", "command": "<name>", ...}.
 * The handler below owns everything that can go wrong:
 * authentication, rate limiting, input validation, and state
 * changes. The socket layer only moves bytes.
 *
 * SECURITY MODEL (read before touching this file):
 * - LAN only: the server binds a site-local address, never
 *   0.0.0.0, and the app never forwards a port. There is no
 *   internet path to this endpoint by construction.
 * - The pairing code is the ONLY credential. It is shown on
 *   the TV, it is 6 digits, it can be regenerated from
 *   Settings, and revoking regenerates it (old codes die).
 * - Every request carries the code; there is no session, no
 *   "remember this phone", and no command that reads
 *   secrets: status reports counts and ids only — never the
 *   arrow code, never the TMDB key, never IPTV credentials.
 * - After [MAX_FAILURES] wrong codes the handler refuses
 *   everything (even a correct code) until [LOCKOUT_MS]
 *   passes. Failures never crash and never change state:
 *   a rejected command is a no-op that returns an error.
 *
 * COMMANDS (v1, nothing more):
 * - status: current style, theme id + options, lock flags,
 *   whether an arrow code is set (never the code itself),
 *   library title count.
 * - setStyle: style must be exactly "clean", "tv" or "watch".
 * - setTheme: themeId must be one of the TV's preset ids.
 * - setAppLock / setFolderLock: "enabled" must be a JSON boolean.
 * - setArrowCode: arrowCode must be a valid ArrowPinCodec
 *   encoding (4–8 U/D/L/R letters); anything else is refused
 *   and the stored code is untouched.
 * - refresh: marks a refresh request the home consumes on its
 *   next build (the server also nudges a live home through
 *   RemoteRefresh when MainActivity is alive).
 */
object RemoteProtocol {

    /** Unprivileged port, documented in phone-remote/PROTOCOL.md. */
    const val PORT = 47819

    const val MAX_FAILURES = 5
    const val LOCKOUT_MS = 60_000L

    /**
     * The mutable state the handler reads/writes. The Android
     * adapter (data/RemoteServer) implements this over Prefs;
     * the sim implements it in memory. [themeOptions] and
     * [libraryTitles] are read-only by the handler.
     */
    interface Store {
        var pairingCode: String
        var style: String
        var themeId: String
        val themeOptions: List<Pair<String, String>>
        var appLockEnabled: Boolean
        var folderLockEnabled: Boolean
        var arrowPin: String
        val libraryTitles: Int
        var refreshPending: Boolean
        var failureCount: Int
        var lockoutUntilMs: Long
    }

    /** A pairing code is exactly 6 decimal digits. */
    fun isValidPairingCode(code: String?): Boolean =
        code != null && code.length == 6 && code.all { it.isDigit() }

    /** The arrow-lock gate decision (v3.19): the remote toggles
     *  suspend enforcement; the per-app / per-folder membership
     *  is untouched, so re-enabling restores exactly the old
     *  locks. MainActivity wires these two functions into
     *  gateLockedApp / openFolderGated (pinned by sim source
     *  checks); the functions themselves are the contract. */
    fun appRequiresPrompt(locked: Boolean, appLockEnabled: Boolean): Boolean =
        locked && appLockEnabled

    fun folderRequiresPrompt(locked: Boolean, folderLockEnabled: Boolean): Boolean =
        locked && folderLockEnabled

    private fun esc(s: String): String {
        val b = StringBuilder()
        for (c in s) {
            when (c) {
                '"' -> b.append("\\\"")
                '\\' -> b.append("\\\\")
                '\n' -> b.append("\\n")
                '\r' -> b.append("\\r")
                '\t' -> b.append("\\t")
                else -> if (c < ' ') b.append(String.format("\\u%04x", c.code)) else b.append(c)
            }
        }
        return b.toString()
    }

    private fun err(code: String): String =
        "{\"ok\":false,\"error\":\"${esc(code)}\"}"

    private fun statusJson(store: Store): String {
        val opts = store.themeOptions.joinToString(",") { (id, name) ->
            "{\"id\":\"${esc(id)}\",\"name\":\"${esc(name)}\"}"
        }
        return "{" +
            "\"ok\":true," +
            "\"style\":\"${esc(store.style)}\"," +
            "\"themeId\":\"${esc(store.themeId)}\"," +
            "\"themeOptions\":[$opts]," +
            "\"appLock\":${store.appLockEnabled}," +
            "\"folderLock\":${store.folderLockEnabled}," +
            "\"lockSet\":${store.arrowPin.isNotEmpty()}," +
            "\"libraryTitles\":${store.libraryTitles}" +
            "}"
    }

    /**
     * Handle one request body. Never throws for bad input: a
     * malformed body, a missing field, or a wrong type is an
     * error reply, never a crash and never a state change.
     * [nowMs] is injected (not read from the clock) so the
     * lockout is sim-testable.
     */
    fun handle(requestJson: String, nowMs: Long, store: Store): String {
        val body = try {
            JsonMini.parse(requestJson).asObj() ?: return err("bad_request")
        } catch (e: Exception) {
            return err("bad_request")
        }
        // ---- authentication (before any command runs) ----
        if (nowMs < store.lockoutUntilMs) return err("locked_out")
        val pairing = body["pairing"].asStr()
        if (pairing == null) return err("missing_code")
        if (!isValidPairingCode(store.pairingCode) || pairing != store.pairingCode) {
            val failures = store.failureCount + 1
            store.failureCount = failures
            if (failures >= MAX_FAILURES) {
                store.lockoutUntilMs = nowMs + LOCKOUT_MS
                store.failureCount = 0
            }
            return err("bad_code")
        }
        store.failureCount = 0
        store.lockoutUntilMs = 0L
        return when (body["command"].asStr()) {
            "status" -> statusJson(store)
            "setStyle" -> {
                val style = body["style"].asStr()
                if (style != "clean" && style != "tv" && style != "watch") err("bad_style")
                else {
                    store.style = style
                    statusJson(store)
                }
            }
            "setTheme" -> {
                val id = body["themeId"].asStr()
                if (id == null || store.themeOptions.none { it.first == id }) err("bad_theme")
                else {
                    store.themeId = id
                    statusJson(store)
                }
            }
            "setAppLock" -> {
                val enabled = body["enabled"].asBool() ?: return err("bad_value")
                store.appLockEnabled = enabled
                statusJson(store)
            }
            "setFolderLock" -> {
                val enabled = body["enabled"].asBool() ?: return err("bad_value")
                store.folderLockEnabled = enabled
                statusJson(store)
            }
            "setArrowCode" -> {
                val code = body["arrowCode"].asStr()
                if (!ArrowPinCodec.isValidEncoded(code)) err("bad_code_value")
                else {
                    store.arrowPin = code!!
                    statusJson(store)
                }
            }
            "refresh" -> {
                store.refreshPending = true
                statusJson(store)
            }
            else -> err("unknown_command")
        }
    }
}
