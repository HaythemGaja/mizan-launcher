package com.mizan.launcherremote

import android.app.Activity
import android.content.SharedPreferences
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sreeb Remote v1.0 — one activity, two screens, no accounts,
 * no cloud, no discovery. The user types the TV's LAN address
 * (shown in Sreeb Settings › Phone remote) and the 6-digit
 * pairing code shown on the same screen, and this app talks
 * only to http://<that IP>:47819/v1/command.
 *
 * Every control sends one command and re-renders from the
 * status the TV returns, so the screen can never disagree
 * with the TV about what actually changed. A failed command
 * changes nothing on screen except the message line.
 */
class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private var ip: String = ""
    private var pairing: String = ""
    private var status: RemoteClient.Status? = null
    private val arrows = StringBuilder()

    // Set while the screen is being filled from a status reply,
    // so programmatic switch/spinner sets never fire commands.
    private var updating = false

    private lateinit var connectScreen: View
    private lateinit var controlScreen: View
    private lateinit var ipInput: EditText
    private lateinit var codeInput: EditText
    private lateinit var connectMsg: TextView
    private lateinit var statusText: TextView
    private lateinit var controlMsg: TextView
    private lateinit var themeSpinner: Spinner
    private lateinit var appLockSwitch: Switch
    private lateinit var folderLockSwitch: Switch
    private lateinit var arrowDisplay: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("sreeb_remote", MODE_PRIVATE)
        ip = prefs.getString("ip", "") ?: ""
        pairing = prefs.getString("pairing", "") ?: ""

        val root = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        root.addView(col)
        connectScreen = buildConnectScreen(col)
        controlScreen = buildControlScreen(col)
        controlScreen.visibility = View.GONE
        setContentView(root)
        ipInput.setText(ip)
        codeInput.setText(pairing)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun title(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 22f
        setPadding(0, 0, 0, dp(16))
    }

    private fun label(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 16f
        setPadding(0, dp(12), 0, dp(4))
    }

    private fun button(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
    }

    // ---- connect screen ----

    private fun buildConnectScreen(col: LinearLayout): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(title("Sreeb Remote"))
        box.addView(TextView(this).apply {
            text = "Control Sreeb on your TV over your home Wi-Fi.\n" +
                "On the TV: Settings › Phone remote — switch it on, " +
                "then type the TV address and pairing code below."
            textSize = 15f
            setPadding(0, 0, 0, dp(8))
        })
        box.addView(label("TV address (from the TV screen)"))
        ipInput = EditText(this).apply { hint = "192.168.1.50" }
        box.addView(ipInput)
        box.addView(label("Pairing code (6 digits, from the TV screen)"))
        codeInput = EditText(this).apply { hint = "123456" }
        box.addView(codeInput)
        connectMsg = TextView(this).apply { textSize = 15f }
        box.addView(connectMsg)
        box.addView(button("Connect") { connect() })
        col.addView(box)
        return box
    }

    private fun connect() {
        ip = ipInput.text.toString().trim()
        pairing = codeInput.text.toString().trim()
        if (ip.isEmpty() || pairing.isEmpty()) {
            connectMsg.text = "Type the TV address and the pairing code first."
            return
        }
        connectMsg.text = "Connecting…"
        send("status") { result ->
            when (result) {
                is RemoteClient.Result.Ok -> {
                    prefs.edit().putString("ip", ip).putString("pairing", pairing).apply()
                    applyStatus(result.status)
                    connectScreen.visibility = View.GONE
                    controlScreen.visibility = View.VISIBLE
                    controlMsg.text = "Connected."
                }
                is RemoteClient.Result.Err -> {
                    connectMsg.text = "Could not connect: ${friendlyError(result.error)}"
                }
            }
        }
    }

    // ---- control screen ----

    private fun buildControlScreen(col: LinearLayout): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(title("Sreeb Remote"))
        statusText = TextView(this).apply { textSize = 16f }
        box.addView(statusText)

        box.addView(label("Home style"))
        val styleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        styleRow.addView(button("Clean") { sendStyle("clean") })
        styleRow.addView(button("TV") { sendStyle("tv") })
        styleRow.addView(button("Watch") { sendStyle("watch") })
        box.addView(styleRow)

        box.addView(label("Wallpaper / theme"))
        themeSpinner = Spinner(this)
        themeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (updating) return
                val st = status ?: return
                val picked = st.themeOptions.getOrNull(pos) ?: return
                if (picked.id != st.themeId) sendTheme(picked.id)
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        box.addView(themeSpinner)

        appLockSwitch = Switch(this).apply { text = "App lock" }
        appLockSwitch.setOnCheckedChangeListener { _, checked ->
            if (!updating) sendToggle("setAppLock", checked)
        }
        box.addView(appLockSwitch)
        folderLockSwitch = Switch(this).apply { text = "Folder lock" }
        folderLockSwitch.setOnCheckedChangeListener { _, checked ->
            if (!updating) sendToggle("setFolderLock", checked)
        }
        box.addView(folderLockSwitch)

        box.addView(label("Arrow code (4–8 arrows)"))
        arrowDisplay = TextView(this).apply { textSize = 28f; gravity = Gravity.CENTER_HORIZONTAL }
        box.addView(arrowDisplay)
        val arrowRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        for ((letter, glyph) in listOf("U" to "↑", "D" to "↓", "L" to "←", "R" to "→")) {
            arrowRow.addView(button(glyph) {
                if (arrows.length < 8) {
                    arrows.append(letter)
                    renderArrows()
                }
            })
        }
        box.addView(arrowRow)
        val arrowActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        arrowActions.addView(button("Clear") { arrows.clear(); renderArrows() })
        arrowActions.addView(button("Set code") {
            if (arrows.length < 4) {
                controlMsg.text = "An arrow code needs 4–8 arrows."
            } else {
                sendArrowCode(arrows.toString())
            }
        })
        box.addView(arrowActions)

        controlMsg = TextView(this).apply { textSize = 15f }
        box.addView(controlMsg)
        box.addView(button("Refresh home") { send("refresh") { onCommandResult(it) } })
        box.addView(button("Disconnect") {
            controlScreen.visibility = View.GONE
            connectScreen.visibility = View.VISIBLE
        })
        col.addView(box)
        return box
    }

    private fun renderArrows() {
        arrowDisplay.text = arrows.map {
            when (it) {
                'U' -> "↑"
                'D' -> "↓"
                'L' -> "←"
                'R' -> "→"
                else -> "?"
            }
        }.joinToString(" ")
    }

    private fun applyStatus(st: RemoteClient.Status) {
        status = st
        updating = true
        statusText.text = "Home: ${styleName(st.style)} · Theme: ${themeName(st)} · " +
            "Library: ${st.libraryTitles} titles · " +
            "Arrow code: ${if (st.lockSet) "set" else "not set"}"
        val names = st.themeOptions.map { it.name }
        themeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val idx = st.themeOptions.indexOfFirst { it.id == st.themeId }.coerceAtLeast(0)
        if (names.isNotEmpty()) themeSpinner.setSelection(idx)
        appLockSwitch.isChecked = st.appLock
        folderLockSwitch.isChecked = st.folderLock
        updating = false
    }

    private fun styleName(style: String): String = when (style) {
        "clean" -> "Clean Tracker"
        "tv" -> "TV"
        "watch" -> "Watch"
        else -> style
    }

    private fun themeName(st: RemoteClient.Status): String =
        st.themeOptions.firstOrNull { it.id == st.themeId }?.name ?: st.themeId

    private fun onCommandResult(result: RemoteClient.Result) {
        when (result) {
            is RemoteClient.Result.Ok -> {
                applyStatus(result.status)
                controlMsg.text = "Done."
            }
            is RemoteClient.Result.Err -> {
                controlMsg.text = "Failed: ${friendlyError(result.error)}"
            }
        }
    }

    private fun sendStyle(style: String) =
        send("setStyle", listOf("style" to style)) { onCommandResult(it) }

    private fun sendTheme(themeId: String) =
        send("setTheme", listOf("themeId" to themeId)) { onCommandResult(it) }

    private fun sendToggle(command: String, enabled: Boolean) =
        send(command, listOf("enabled" to enabled)) { onCommandResult(it) }

    private fun sendArrowCode(code: String) =
        send("setArrowCode", listOf("arrowCode" to code)) { onCommandResult(it) }

    private fun friendlyError(error: String): String = when (error) {
        "bad_code", "missing_code" -> "wrong or missing pairing code — check the TV screen"
        "locked_out" -> "too many wrong codes — wait a minute and try again"
        "bad_style", "bad_theme", "bad_value", "bad_code_value" -> "the TV refused that value ($error)"
        "unknown_command" -> "this TV version does not know that command"
        "bad_reply" -> "the TV answered something unexpected"
        else -> error
    }

    // ---- transport: one POST, background thread, UI-thread reply ----

    private fun send(command: String, params: List<Pair<String, Any>> = emptyList(), onResult: (RemoteClient.Result) -> Unit) {
        val body = RemoteClient.requestJson(pairing, command, params)
        val endpoint = RemoteClient.url(ip)
        Thread {
            val result = try {
                val conn = URL(endpoint).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
                conn.disconnect()
                RemoteClient.parseResponse(text)
            } catch (e: Exception) {
                RemoteClient.Result.Err("unreachable (${e.javaClass.simpleName})")
            }
            runOnUiThread { onResult(result) }
        }.apply { isDaemon = true; start() }
    }
}
