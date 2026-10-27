package com.softalarm.app

import android.Manifest
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val dayNames = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private var alarms: MutableList<Alarm> = mutableListOf()

    // Alarm tab
    private lateinit var clockTime: TextView
    private lateinit var clockDate: TextView
    private lateinit var alarmList: LinearLayout
    private lateinit var fab: TextView
    private lateinit var proBanner: TextView
    private lateinit var payBanner: TextView
    private lateinit var flow: PaymentFlow
    private lateinit var accounts: AccountGateway
    private lateinit var restoreFlow: RestoreFlow
    private lateinit var proSync: ProSync
    private lateinit var deleteFlow: DeleteAccountFlow
    private lateinit var restoreBanner: TextView
    private var lastProSync = 0L
    private lateinit var permBanner: TextView
    private var reopenPerms = false
    private var cloud: CloudResult? = null
    private var lastCloudSync = 0L
    private val REQ_PICK = 77
    private var pickedUriCallback: ((String) -> Unit)? = null
    private val timeFmt = SimpleDateFormat("h:mm:ss a", Locale.US)
    private val dateFmt = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault())

    // Tabs
    private val panels = mutableListOf<View>()
    private val tabButtons = mutableListOf<ImageView>()

    // Timer tab
    private lateinit var timerDisplay: TextView
    private lateinit var timerSetup: LinearLayout
    private lateinit var timerStartBtn: Button
    private lateinit var etHours: EditText
    private lateinit var etMinutes: EditText
    private lateinit var etSeconds: EditText

    // Stopwatch tab
    private lateinit var swDisplay: TextView
    private lateinit var swStartBtn: Button
    private lateinit var lapList: LinearLayout
    private var swRunning = false
    private var swStartedAt = 0L
    private var swAccum = 0L
    private var lapCount = 0

    // Runs every 250 ms while the screen is visible
    private val ticker = object : Runnable {
        override fun run() {
            updateClock()
            refreshTimer()
            updateStopwatch()
            handler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("softalarm_timer", MODE_PRIVATE)
        buildUi()

        // The "Unlock Pro" journey. FAKE or REAL payments are chosen in ProConfig.kt (PLUG-IN POINT 2).
        val gateway = ProGatewayFactory.create()
        flow = PaymentFlow(
            gateway = gateway,
            store = PrefsProStore(this),
            schedule = { delayMs, task -> handler.postDelayed({ task() }, delayMs) },
            onState = { state -> showPayState(state) },
            maxChecks = ProConfig.checkMaxAttempts,
            checkEveryMs = ProConfig.checkEveryMs
        )
        // Accounts: Pro belongs to an account, so it survives a reinstall / new phone (PLUG-IN POINT 4)
        accounts = AccountGatewayFactory.create { method, onPicked -> showFakeAccountChooser(method, onPicked) }
        restoreFlow = RestoreFlow(
            accounts = accounts,
            gateway = gateway,
            store = PrefsProStore(this),
            onState = { state -> showRestoreState(state) },
            confirmSwitch = { answer -> confirmSwitchDialog(answer) }
        )
        deleteFlow = DeleteAccountFlow(gateway, accounts, PrefsProStore(this)) { state -> showDeleteState(state) }
        // Quietly follows the server: unlocks if paid elsewhere, locks again only after a refund
        proSync = ProSync(
            gateway = gateway,
            store = PrefsProStore(this),
            onRevoked = { onProRevoked() },
            onUnlocked = { onProUnlockedBySync() }
        )

        // Android forgets alarms after a force-stop or a backup restore: set every enabled alarm again when the app opens
        for (a in AlarmStore.load(this)) {
            if (a.enabled) AlarmScheduler.schedule(this, a)
        }

        // softalarm://paid : the payment page sends the user back to the app with this link
        addOnNewIntentListener { newIntent -> handleDeepLink(newIntent) }
        handleDeepLink(intent)

        // first launch: walk the user through every permission the alarm needs
        if (!prefs.getBoolean("perm_prompted", false) && AppPermissions.missingCount(this) > 0) {
            prefs.edit().putBoolean("perm_prompted", true).apply()
            handler.post { showPermissionsDialog() }
        }
    }

    override fun onResume() {
        super.onResume()
        alarms = AlarmStore.load(this)
        renderAlarms()
        refreshProBanner()
        refreshPermBanner()
        syncCloud()
        if (reopenPerms) {
            reopenPerms = false
            if (AppPermissions.missingCount(this) > 0) showPermissionsDialog()
        }
        flow.onReturnedToApp()   // came back from the browser? check the payment
        syncPro()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    // ------------------------------------------------------------------ UI

    private fun lp(w: Int, h: Int, top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(w, h)
        p.topMargin = dp(top)
        p.bottomMargin = dp(bottom)
        return p
    }

    private fun buildUi() {
        val root = FrameLayout(this)
        root.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Colors.CREAM, Colors.PEACH, Colors.BLUSH)
        )

        val scroll = ScrollView(this)
        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(16), dp(28), dp(16), dp(120))
        scroll.addView(content, FrameLayout.LayoutParams(MATCH, WRAP))
        root.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))

        val title = TextView(this)
        title.text = "SoftAlarm"
        title.textSize = 28f
        title.typeface = Typeface.DEFAULT_BOLD
        title.setTextColor(Colors.PLUM)
        title.gravity = Gravity.CENTER
        content.addView(title, lp(MATCH, WRAP))

        val sub = TextView(this)
        sub.text = "Gentle reminders, wherever you are"
        sub.textSize = 13.5f
        sub.setTextColor(Colors.SOFT_TEXT)
        sub.gravity = Gravity.CENTER
        content.addView(sub, lp(MATCH, WRAP, 2, 18))

        // Icon tab bar: Alarm / Timer / Stopwatch
        val tabBar = LinearLayout(this)
        tabBar.orientation = LinearLayout.HORIZONTAL
        tabBar.gravity = Gravity.CENTER
        val icons = intArrayOf(R.drawable.ic_alarm, R.drawable.ic_timer, R.drawable.ic_stopwatch)
        val names = listOf("Alarm", "Timer", "Stopwatch")
        for (i in icons.indices) {
            val b = ImageView(this)
            b.setImageResource(icons[i])
            b.contentDescription = names[i]
            b.setPadding(dp(14), dp(14), dp(14), dp(14))
            b.setOnClickListener { selectTab(i) }
            tabButtons.add(b)
            val params = LinearLayout.LayoutParams(dp(52), dp(52))
            params.setMargins(dp(5), 0, dp(5), 0)
            tabBar.addView(b, params)
        }
        content.addView(tabBar, lp(MATCH, WRAP, 0, 20))

        panels.add(buildAlarmPanel())
        panels.add(buildTimerPanel())
        panels.add(buildStopwatchPanel())
        for (p in panels) content.addView(p, lp(MATCH, WRAP))

        // Floating + button (only on the Alarm tab)
        fab = TextView(this)
        fab.text = "+"
        fab.textSize = 30f
        fab.setTextColor(Colors.WHITE)
        fab.gravity = Gravity.CENTER
        val circle = GradientDrawable()
        circle.shape = GradientDrawable.OVAL
        circle.setColor(Colors.CORAL)
        fab.background = circle
        fab.elevation = dp(6).toFloat()
        fab.setOnClickListener { showAddDialog() }
        val fabParams = FrameLayout.LayoutParams(dp(58), dp(58), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        fabParams.bottomMargin = dp(28)
        root.addView(fab, fabParams)

        setContentView(root)
        selectTab(0)
    }

    private fun selectTab(index: Int) {
        for (i in panels.indices) {
            panels[i].visibility = if (i == index) View.VISIBLE else View.GONE
            val on = i == index
            tabButtons[i].background = rounded(if (on) Colors.CORAL else Colors.GLASS_LIGHT, 16)
            tabButtons[i].setColorFilter(if (on) Colors.WHITE else Colors.MAUVE)
        }
        fab.visibility = if (index == 0) View.VISIBLE else View.GONE
    }

    private fun smallCaption(text: String): TextView {
        val t = TextView(this)
        t.text = text.uppercase(Locale.US)
        t.textSize = 12f
        t.typeface = Typeface.DEFAULT_BOLD
        t.letterSpacing = 0.08f
        t.setTextColor(Colors.MAUVE)
        return t
    }

    private fun bigDisplay(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = 48f
        t.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        t.setTextColor(Colors.PLUM)
        t.gravity = Gravity.CENTER
        t.background = rounded(Colors.GLASS_LIGHT, 24)
        t.setPadding(dp(12), dp(34), dp(12), dp(34))
        return t
    }

    // ------------------------------------------------------------------ Alarm tab

    private fun buildAlarmPanel(): LinearLayout {
        val p = LinearLayout(this)
        p.orientation = LinearLayout.VERTICAL

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.gravity = Gravity.CENTER_HORIZONTAL
        card.background = rounded(Colors.GLASS_LIGHT, 24)
        card.setPadding(dp(20), dp(24), dp(20), dp(24))

        clockTime = TextView(this)
        clockTime.textSize = 38f
        clockTime.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        clockTime.setTextColor(Colors.PLUM)
        card.addView(clockTime, lp(WRAP, WRAP))

        clockDate = TextView(this)
        clockDate.textSize = 13f
        clockDate.setTextColor(Colors.MAUVE)
        card.addView(clockDate, lp(WRAP, WRAP, 4, 0))

        permBanner = TextView(this)
        permBanner.text = "⚠  Fix settings so alarms ring on time"
        permBanner.textSize = 14f
        permBanner.typeface = Typeface.DEFAULT_BOLD
        permBanner.setTextColor(Colors.PLUM)
        permBanner.gravity = Gravity.CENTER
        permBanner.background = rounded(Color.parseColor("#FFE3A3"), 16)
        permBanner.setPadding(dp(16), dp(12), dp(16), dp(12))
        permBanner.setOnClickListener { showPermissionsDialog() }
        p.addView(permBanner, lp(MATCH, WRAP, 0, 10))

        proBanner = TextView(this)
        proBanner.text = "★  Unlock Pro voices"
        proBanner.textSize = 14f
        proBanner.typeface = Typeface.DEFAULT_BOLD
        proBanner.setTextColor(Colors.WHITE)
        proBanner.gravity = Gravity.CENTER
        proBanner.background = rounded(Colors.CORAL, 16)
        proBanner.setPadding(dp(16), dp(12), dp(16), dp(12))
        proBanner.setOnClickListener { showProDialog() }
        p.addView(proBanner, lp(MATCH, WRAP, 0, 14))

        payBanner = TextView(this)
        payBanner.textSize = 13.5f
        payBanner.setTextColor(Colors.PLUM)
        payBanner.gravity = Gravity.CENTER
        payBanner.background = rounded(Colors.GLASS, 16)
        payBanner.setPadding(dp(16), dp(12), dp(16), dp(12))
        payBanner.visibility = View.GONE
        payBanner.setOnClickListener { onPayBannerTap() }
        p.addView(payBanner, lp(MATCH, WRAP, 0, 14))

        restoreBanner = TextView(this)
        restoreBanner.textSize = 13.5f
        restoreBanner.setTextColor(Colors.PLUM)
        restoreBanner.gravity = Gravity.CENTER
        restoreBanner.background = rounded(Colors.GLASS, 16)
        restoreBanner.setPadding(dp(16), dp(12), dp(16), dp(12))
        restoreBanner.visibility = View.GONE
        restoreBanner.setOnClickListener {
            if (restoreFlow.phase != RestorePhase.SIGNING_IN && restoreFlow.phase != RestorePhase.CHECKING) restoreFlow.reset()
        }
        p.addView(restoreBanner, lp(MATCH, WRAP, 0, 14))

        p.addView(card, lp(MATCH, WRAP, 0, 24))
        p.addView(smallCaption("Your alarms"), lp(WRAP, WRAP, 0, 10))

        alarmList = LinearLayout(this)
        alarmList.orientation = LinearLayout.VERTICAL
        p.addView(alarmList, lp(MATCH, WRAP))

        val test = pillButton("🔔  Test alarm in 10 seconds", false)
        test.setOnClickListener {
            AlarmScheduler.scheduleTest(this, 10)
            Toast.makeText(this, "Now lock your phone. The alarm rings in 10 seconds.", Toast.LENGTH_LONG).show()
        }
        p.addView(test, lp(MATCH, WRAP, 16, 0))

        val account = pillButton("👤  Account & restore", false)
        account.setOnClickListener { showAccountDialog() }
        p.addView(account, lp(MATCH, WRAP, 10, 0))
        return p
    }

    private fun updateClock() {
        val now = Date()
        clockTime.text = timeFmt.format(now)
        clockDate.text = dateFmt.format(now)
    }

    private fun describeDays(a: Alarm): String {
        if (a.days.isEmpty()) return "Once"
        if (a.days.size == 7) return "Every day"
        return a.days.sorted().joinToString(" ") { dayNames[it] }
    }

    private fun renderAlarms() {
        alarmList.removeAllViews()
        if (alarms.isEmpty()) {
            val empty = TextView(this)
            empty.text = "No alarms yet. Tap + to add a gentle wake-up."
            empty.textSize = 14f
            empty.gravity = Gravity.CENTER
            empty.setTextColor(Colors.MAUVE)
            empty.background = rounded(Colors.GLASS_LIGHT, 18)
            empty.setPadding(dp(16), dp(36), dp(16), dp(36))
            alarmList.addView(empty, lp(MATCH, WRAP))
            return
        }
        val sorted = alarms.sortedWith(compareBy<Alarm>({ it.hour }, { it.minute }))
        for (a in sorted) alarmList.addView(alarmCard(a), lp(MATCH, WRAP, 0, 10))
    }

    private fun alarmCard(a: Alarm): View {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.HORIZONTAL
        card.gravity = Gravity.CENTER_VERTICAL
        card.background = rounded(Colors.GLASS, 18)
        card.setPadding(dp(18), dp(14), dp(12), dp(14))
        card.alpha = if (a.enabled) 1f else 0.5f

        val info = LinearLayout(this)
        info.orientation = LinearLayout.VERTICAL

        val timeText = TextView(this)
        timeText.text = formatTime(a.hour, a.minute)
        timeText.textSize = 22f
        timeText.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        timeText.setTextColor(Colors.PLUM)
        info.addView(timeText)

        val meta = TextView(this)
        val labelPart = if (a.label.isBlank()) "" else a.label + " · "
        val lockedMark = if (Voices.isPro(a.voice) && !ProManager.isPro(this)) "  🔒 Pro" else ""
        meta.text = labelPart + describeDays(a) + " · " + Voices.labelFor(a.voice) + lockedMark
        meta.textSize = 12.5f
        meta.setTextColor(Colors.MAUVE)
        info.addView(meta)

        card.addView(info, LinearLayout.LayoutParams(0, WRAP, 1f))

        val del = TextView(this)
        del.text = "✕"
        del.textSize = 18f
        del.setTextColor(Color.parseColor("#C98B84"))
        del.setPadding(dp(10), dp(6), dp(14), dp(6))
        del.setOnClickListener { deleteAlarm(a) }
        card.addView(del)

        val sw = Switch(this)
        sw.isChecked = a.enabled
        sw.setOnCheckedChangeListener { _, checked ->
            a.enabled = checked
            AlarmStore.save(this, alarms)
            if (checked) {
                AlarmScheduler.schedule(this, a)
                toastNextRing(a)
            } else {
                AlarmScheduler.cancel(this, a.id)
            }
            card.alpha = if (checked) 1f else 0.5f
        }
        card.addView(sw)
        return card
    }

    private fun deleteAlarm(a: Alarm) {
        AlarmScheduler.cancel(this, a.id)
        alarms.remove(a)
        AlarmStore.save(this, alarms)
        renderAlarms()
    }

    private fun toastNextRing(a: Alarm) {
        val t = AlarmScheduler.nextTrigger(a) ?: return
        val mins = (t - System.currentTimeMillis() + 59999) / 60000
        val d = mins / (60 * 24)
        val h = (mins / 60) % 24
        val m = mins % 60
        val parts = mutableListOf<String>()
        if (d > 0) parts.add("$d days")
        if (h > 0) parts.add("$h hours")
        parts.add("$m minutes")
        Toast.makeText(this, "Alarm rings in " + parts.joinToString(" "), Toast.LENGTH_SHORT).show()
    }

    private fun showAddDialog() {
        val now = Calendar.getInstance()
        var hour = now.get(Calendar.HOUR_OF_DAY)
        var minute = now.get(Calendar.MINUTE)
        val selectedDays = mutableSetOf<Int>()

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(8), dp(20), dp(8))

        box.addView(smallCaption("Time"), lp(WRAP, WRAP, 8, 4))
        val timeBtn = pillButton(formatTime(hour, minute), false)
        timeBtn.setOnClickListener {
            TimePickerDialog(this, { _, h, m ->
                hour = h
                minute = m
                timeBtn.text = formatTime(h, m)
            }, hour, minute, false).show()
        }
        box.addView(timeBtn, lp(MATCH, WRAP))

        box.addView(smallCaption("Label"), lp(WRAP, WRAP, 14, 0))
        val labelEt = EditText(this)
        labelEt.hint = "e.g. Morning walk"
        labelEt.setSingleLine(true)
        labelEt.inputType = InputType.TYPE_CLASS_TEXT
        box.addView(labelEt, lp(MATCH, WRAP))

        box.addView(smallCaption("Voice"), lp(WRAP, WRAP, 14, 0))
        val voiceSp = Spinner(this)
        val unlocked = ProManager.isPro(this)
        voiceSp.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            Voices.all.map { if (it.pro && !unlocked) it.displayLabel + "  🔒" else it.displayLabel }
        )
        box.addView(voiceSp, lp(MATCH, WRAP))

        val fileInfo = TextView(this)
        fileInfo.textSize = 12.5f
        fileInfo.setTextColor(Colors.MAUVE)
        fileInfo.visibility = View.GONE
        box.addView(fileInfo, lp(MATCH, WRAP, 4, 0))

        var pickedUri = ""
        var lastPos = 0
        voiceSp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == lastPos) return
                val v = Voices.all[position]
                if (v.pro && !ProManager.isPro(this@MainActivity)) {
                    voiceSp.setSelection(lastPos)   // go back to the previous (free) choice
                    showProDialog()
                    return
                }
                lastPos = position
                if (v.key == "custom") {
                    fileInfo.visibility = View.VISIBLE
                    fileInfo.text = if (pickedUri.isEmpty()) "No file chosen yet" else "Selected: " + displayName(pickedUri)
                    pickFile { uri ->
                        pickedUri = uri
                        fileInfo.text = "Selected: " + displayName(uri)
                    }
                } else {
                    fileInfo.visibility = View.GONE
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        box.addView(smallCaption("Repeat"), lp(WRAP, WRAP, 14, 6))
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val letters = listOf("S", "M", "T", "W", "T", "F", "S")
        for (i in letters.indices) {
            val chip = TextView(this)
            chip.text = letters[i]
            chip.gravity = Gravity.CENTER
            chip.typeface = Typeface.DEFAULT_BOLD
            chip.setTextColor(Colors.MAUVE)
            chip.background = rounded(Colors.SOFT_BTN, 10)
            chip.setPadding(0, dp(10), 0, dp(10))
            chip.setOnClickListener {
                if (selectedDays.contains(i)) {
                    selectedDays.remove(i)
                    chip.setTextColor(Colors.MAUVE)
                    chip.background = rounded(Colors.SOFT_BTN, 10)
                } else {
                    selectedDays.add(i)
                    chip.setTextColor(Colors.WHITE)
                    chip.background = rounded(Colors.CORAL, 10)
                }
            }
            val params = LinearLayout.LayoutParams(0, WRAP, 1f)
            params.setMargins(dp(2), 0, dp(2), 0)
            row.addView(chip, params)
        }
        box.addView(row, lp(MATCH, WRAP))

        val scroller = ScrollView(this)
        scroller.addView(box)

        AlertDialog.Builder(this)
            .setTitle("New alarm")
            .setView(scroller)
            .setPositiveButton("Save") { _, _ ->
                val chosen = Voices.all[voiceSp.selectedItemPosition]
                var voiceKey = chosen.key
                if (chosen.pro && !ProManager.isPro(this)) voiceKey = "default"
                if (voiceKey == "custom" && pickedUri.isEmpty()) voiceKey = "default"
                val alarm = Alarm(
                    AlarmStore.newId(), hour, minute,
                    labelEt.text.toString().trim(),
                    voiceKey,
                    selectedDays.sorted(), true,
                    if (voiceKey == "custom") pickedUri else ""
                )
                alarms.add(alarm)
                AlarmStore.save(this, alarms)
                AlarmScheduler.schedule(this, alarm)
                renderAlarms()
                toastNextRing(alarm)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------------ Pro unlock + file picker

    // Silent Firebase sign-in + Pro status (at most once a minute)
    private fun syncCloud() {
        val now = System.currentTimeMillis()
        if (now - lastCloudSync < 60_000L) return
        lastCloudSync = now
        CloudSync.sync(this) { result ->
            cloud = result
            refreshProBanner()
        }
    }

    private fun accountText(): String {
        val a = accounts.current()
        val who = if (a.signedIn) "Signed in as " + a.label else "Guest (sign in to keep Pro on a new phone)"
        return if (ProConfig.USE_FAKE_PAYMENTS) "Test mode: payments are simulated.  Account: " + who else "Account: " + who
    }

    private fun refreshPermBanner() {
        permBanner.visibility = if (AppPermissions.missingCount(this) > 0) View.VISIBLE else View.GONE
    }

    private fun showPermissionsDialog() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(8), dp(20), dp(4))

        val intro = TextView(this)
        intro.text = "For alarms that ring exactly on time, even when the screen is off, please allow each item below. " +
            "After each one, press Back to return here."
        intro.textSize = 14f
        intro.setTextColor(Colors.PLUM)
        box.addView(intro, lp(MATCH, WRAP, 0, 12))

        var dlg: AlertDialog? = null
        for (item in AppPermissions.items(this)) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.VERTICAL
            row.background = rounded(Colors.SOFT_BTN, 14)
            row.setPadding(dp(14), dp(12), dp(14), dp(12))

            val title = TextView(this)
            title.text = (if (item.granted) "✓  " else if (item.manual) "•  " else "✗  ") + item.title
            title.textSize = 15f
            title.typeface = Typeface.DEFAULT_BOLD
            title.setTextColor(if (item.granted) Color.parseColor("#2E7D4F") else Colors.PLUM)
            row.addView(title)

            val why = TextView(this)
            why.text = item.why
            why.textSize = 12.5f
            why.setTextColor(Colors.SOFT_TEXT)
            row.addView(why, lp(MATCH, WRAP, 2, 0))

            if (!item.granted) {
                val b = pillButton(if (item.manual) "Open app settings" else "Allow", true)
                b.setOnClickListener {
                    dlg?.dismiss()
                    reopenPerms = true
                    AppPermissions.fix(this, item.key)
                }
                row.addView(b, lp(MATCH, WRAP, 8, 0))
            }
            box.addView(row, lp(MATCH, WRAP, 0, 8))
        }

        dlg = AlertDialog.Builder(this)
            .setTitle("Make alarms ring on time")
            .setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton("Close") { _, _ -> reopenPerms = false }
            .create()
        dlg?.show()
    }

    private fun refreshProBanner() {
        // The Unlock button only appears when at least one voice is Pro (none yet) and the user is not Pro
        proBanner.visibility = if (!Voices.hasProVoices() || ProManager.isPro(this)) View.GONE else View.VISIBLE
    }

    private fun showProDialog() {
        val proCount = Voices.all.count { it.pro }
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(8), dp(20), dp(4))

        val info = TextView(this)
        info.text = "Free forever: the phone alarm tone, the SoftAlarm soft tune, your own audio file, and all the free voices.\n\n" +
            "Pro: " + proCount + " more voices. One-time payment of " + ProConfig.PRICE_LABEL +
            ". You pay on a secure page; your card details never touch SoftAlarm."
        info.textSize = 14f
        info.setTextColor(Colors.PLUM)
        box.addView(info, lp(MATCH, WRAP, 0, 12))

        val account = TextView(this)
        account.text = accountText()
        account.textSize = 12f
        account.setTextColor(Colors.MAUVE)
        box.addView(account, lp(MATCH, WRAP, 0, 10))

        var dlg: AlertDialog? = null
        fun addButton(label: String, primary: Boolean, action: () -> Unit) {
            val b = pillButton(label, primary)
            b.setOnClickListener {
                dlg?.dismiss()
                action()
            }
            box.addView(b, lp(MATCH, WRAP, 0, 8))
        }
        addButton("Unlock Pro  (" + ProConfig.PRICE_LABEL + ")", true) { startPurchase() }
        addButton("I've paid, check now", false) { flow.checkNow() }
        addButton("Restore on a new phone", false) { showRestoreChoice() }
        if (ProConfig.USE_FAKE_PAYMENTS) addButton("Reset test purchase", false) { resetFakePurchase() }

        dlg = AlertDialog.Builder(this)
            .setTitle("Unlock Pro voices")
            .setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton("Close", null)
            .create()
        dlg?.show()
    }

    private fun startPurchase() {
        flow.buy { url -> openPaymentPage(url) }
    }

    // Opens the payment page. Fake mode shows a pretend page; real mode opens the browser (https only).
    private fun openPaymentPage(url: String): Boolean {
        if (ProConfig.USE_FAKE_PAYMENTS && url.startsWith(FakeBackend.FAKE_URL)) {
            showFakeCheckout()
            return true
        }
        if (!url.startsWith("https://")) return false
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            true
        } catch (e: Exception) {
            false
        }
    }

    // A pretend payment page, so the whole journey can be tried with no network and no money
    private fun showFakeCheckout() {
        AlertDialog.Builder(this)
            .setTitle("Test checkout (no real money)")
            .setMessage(
                "SoftAlarm Pro  ·  " + ProConfig.PRICE_LABEL + "\n\n" +
                    "This is a simulated payment page. In the real app this is the secure Polar page."
            )
            .setPositiveButton("Pay " + ProConfig.PRICE_LABEL) { _, _ ->
                FakeServer.backend.pay()
                flow.onReturnedToApp()
            }
            .setNegativeButton("Card declined") { _, _ ->
                Toast.makeText(this, "Card declined (simulated). Nothing was charged.", Toast.LENGTH_SHORT).show()
                flow.onReturnedToApp()
            }
            .setNeutralButton("Close") { _, _ -> flow.onReturnedToApp() }
            .setOnCancelListener { flow.onReturnedToApp() }
            .show()
    }

    private fun resetFakePurchase() {
        FakeServer.backend.reset()
        ProManager.setPro(this, false)
        flow.reset()
        refreshProBanner()
        Toast.makeText(this, "Test purchase reset. Pro voices are locked again.", Toast.LENGTH_SHORT).show()
    }

    // ---------- Accounts, restore and refunds ----------

    private fun syncPro() {
        val now = System.currentTimeMillis()
        if (now - lastProSync < 60_000L) return
        lastProSync = now
        proSync.refresh()
    }

    private fun onProRevoked() {
        refreshProBanner()
        renderAlarms()
        Toast.makeText(this, "Your Pro was removed because the payment was refunded.", Toast.LENGTH_LONG).show()
    }

    private fun onProUnlockedBySync() {
        refreshProBanner()
        renderAlarms()
        Toast.makeText(this, "Pro unlocked from your account.", Toast.LENGTH_LONG).show()
    }

    private fun showAccountDialog() {
        val info = accounts.current()
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(8), dp(20), dp(4))

        val text = TextView(this)
        text.text = if (!Voices.hasProVoices()) {
            "SoftAlarm has no paid features right now, so there is nothing to sign in for.\n\n" +
                "You can still read the privacy policy or delete your online account."
        } else if (info.signedIn) {
            "Signed in as " + info.label + ".\n\nYour Pro follows this account, so you can restore it on a new phone."
        } else {
            "You are using SoftAlarm as a guest.\n\nA guest is forgotten if you reinstall the app or change phone. " +
                "Signing in (optional) keeps your Pro safe."
        }
        text.textSize = 14f
        text.setTextColor(Colors.PLUM)
        box.addView(text, lp(MATCH, WRAP, 0, 8))

        val idText = TextView(this)
        idText.text = "Account ID: " + (CloudSync.userId(this) ?: if (ProConfig.USE_FAKE_PAYMENTS) "(test mode)" else "(not connected yet)")
        idText.textSize = 11.5f
        idText.setTextColor(Colors.MAUVE)
        idText.setTextIsSelectable(true)
        box.addView(idText, lp(MATCH, WRAP, 0, 12))

        var dlg: AlertDialog? = null
        fun addButton(label: String, primary: Boolean, action: () -> Unit) {
            val b = pillButton(label, primary)
            b.setOnClickListener {
                dlg?.dismiss()
                action()
            }
            box.addView(b, lp(MATCH, WRAP, 0, 8))
        }
        if (Voices.hasProVoices()) {
            if (info.signedIn) {
                addButton("Check my purchases", true) { restoreFlow.checkAccount() }
                addButton("Sign out", false) { confirmSignOut() }
            } else {
                addButton("Sign in with Google", true) { restoreFlow.restore(SignInMethod.GOOGLE) }
                addButton("Sign in with email", true) { restoreFlow.restore(SignInMethod.EMAIL) }
            }
        }
        addButton("Privacy policy", false) { openLegalPage(ProConfig.privacyUrl) }
        addButton("Delete my account", false) { confirmDeleteAccount() }
        if (ProConfig.USE_FAKE_PAYMENTS && Voices.hasProVoices()) {
            addButton("Test: pretend I have a new phone", false) { simulateNewPhone() }
            addButton("Test: pretend my payment was refunded", false) { simulateRefund() }
        }

        dlg = AlertDialog.Builder(this)
            .setTitle("Account")
            .setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton("Close", null)
            .create()
        dlg?.show()
    }

    private fun showRestoreChoice() {
        if (accounts.current().signedIn) {
            restoreFlow.checkAccount()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Restore your purchase")
            .setMessage("Sign in with the account you used when you paid. Pro will be unlocked on this phone.")
            .setPositiveButton("Google") { _, _ -> restoreFlow.restore(SignInMethod.GOOGLE) }
            .setNegativeButton("Email") { _, _ -> restoreFlow.restore(SignInMethod.EMAIL) }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun openLegalPage(url: String) {
        if (!ProConfig.serverConfigured) {
            Toast.makeText(this, "Your server address is not set yet (see ProConfig.kt), so this page cannot open.", Toast.LENGTH_LONG).show()
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, "No browser found.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDeleteAccount() {
        AlertDialog.Builder(this)
            .setTitle("Delete your account?")
            .setMessage(
                "This permanently deletes your SoftAlarm account and its data on our server.\n\n" +
                    "Your Pro unlock belongs to the account, so it is removed and cannot be restored or refunded here.\n\n" +
                    "Your alarms on this phone are not affected."
            )
            .setPositiveButton("Delete") { _, _ -> deleteFlow.start() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteState(state: DeleteState) {
        when (state.phase) {
            DeletePhase.DELETING -> Toast.makeText(this, state.message, Toast.LENGTH_SHORT).show()
            DeletePhase.DONE -> {
                flow.reset()
                restoreFlow.reset()
                refreshProBanner()
                renderAlarms()
                Toast.makeText(this, state.message, Toast.LENGTH_LONG).show()
            }
            DeletePhase.FAILED -> Toast.makeText(this, state.message, Toast.LENGTH_LONG).show()
            DeletePhase.IDLE -> {}
        }
    }

    private fun confirmSignOut() {
        AlertDialog.Builder(this)
            .setTitle("Sign out?")
            .setMessage("Pro voices will lock on this phone until you sign in again. Your purchase stays safe on your account.")
            .setPositiveButton("Sign out") { _, _ -> doSignOut() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doSignOut() {
        accounts.signOut()
        ProManager.setPro(this, false)
        flow.reset()
        restoreFlow.reset()
        refreshProBanner()
        renderAlarms()
        Toast.makeText(this, "Signed out. You are a guest again.", Toast.LENGTH_SHORT).show()
    }

    // Asked only when a guest who already paid is about to switch to a different, existing account
    private fun confirmSwitchDialog(answer: (Boolean) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("Switch account?")
            .setMessage(
                "You already unlocked Pro on this guest session. That Pro will NOT follow you to the account " +
                    "you are signing into. Switch anyway?"
            )
            .setPositiveButton("Switch") { _, _ -> answer(true) }
            .setNegativeButton("Stay") { _, _ -> answer(false) }
            .setOnCancelListener { answer(false) }
            .show()
    }

    private fun showRestoreState(state: RestoreState) {
        if (state.phase == RestorePhase.IDLE) {
            restoreBanner.visibility = View.GONE
            return
        }
        val hint = when (state.phase) {
            RestorePhase.SIGNING_IN, RestorePhase.CHECKING -> ""
            else -> "\n(tap to dismiss)"
        }
        restoreBanner.text = state.message + hint
        restoreBanner.visibility = View.VISIBLE
        if (state.phase == RestorePhase.RESTORED) {
            refreshProBanner()
            renderAlarms()
            Toast.makeText(this, "Pro is unlocked on this phone. Thank you!", Toast.LENGTH_LONG).show()
        }
    }

    // Offered once, right after the first purchase
    private fun maybeNudgeSignIn() {
        if (!Voices.hasProVoices() || accounts.current().signedIn || prefs.getBoolean("nudged_signin", false)) return
        prefs.edit().putBoolean("nudged_signin", true).apply()
        handler.postDelayed({ if (!isFinishing && !isDestroyed) showSaveProDialog() }, 1500)
    }

    private fun showSaveProDialog() {
        AlertDialog.Builder(this)
            .setTitle("Keep your Pro safe")
            .setMessage(
                "Right now you are a guest. If you reinstall the app or change phone, you would have to sign in to get " +
                    "Pro back. Sign in now so it follows you (optional)."
            )
            .setPositiveButton("Google") { _, _ -> restoreFlow.restore(SignInMethod.GOOGLE) }
            .setNegativeButton("Email") { _, _ -> restoreFlow.restore(SignInMethod.EMAIL) }
            .setNeutralButton("Not now", null)
            .show()
    }

    // FAKE sign-in pop-ups (the real Google / email screens replace these at PLUG-IN POINT 4)
    private fun showFakeAccountChooser(method: SignInMethod, onPicked: (String?) -> Unit) {
        if (method == SignInMethod.GOOGLE) {
            val names = arrayOf("alex@example.com", "sam@example.com")
            AlertDialog.Builder(this)
                .setTitle("Choose a Google account (test)")
                .setItems(names) { _, which -> onPicked(names[which]) }
                .setNegativeButton("Cancel") { _, _ -> onPicked(null) }
                .setOnCancelListener { onPicked(null) }
                .show()
        } else {
            val input = EditText(this)
            input.hint = "you@example.com"
            input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            input.setSingleLine(true)
            val holder = LinearLayout(this)
            holder.setPadding(dp(20), dp(8), dp(20), 0)
            holder.addView(input, LinearLayout.LayoutParams(MATCH, WRAP))
            AlertDialog.Builder(this)
                .setTitle("Sign in with email (test)")
                .setView(holder)
                .setPositiveButton("Continue") { _, _ ->
                    val email = input.text.toString().trim()
                    if (email.contains("@") && email.contains(".")) {
                        onPicked(email)
                    } else {
                        Toast.makeText(this, "Please type a valid email address.", Toast.LENGTH_SHORT).show()
                        onPicked(null)
                    }
                }
                .setNegativeButton("Cancel") { _, _ -> onPicked(null) }
                .setOnCancelListener { onPicked(null) }
                .show()
        }
    }

    private fun simulateNewPhone() {
        FakeServer.backend.signOut()
        ProManager.setPro(this, false)
        flow.reset()
        restoreFlow.reset()
        refreshProBanner()
        renderAlarms()
        Toast.makeText(this, "Pretending this is a new phone: fresh guest, Pro locked. Now try Restore.", Toast.LENGTH_LONG).show()
    }

    private fun simulateRefund() {
        FakeServer.backend.refund()
        proSync.refresh()
    }

    private fun handleDeepLink(i: Intent?) {
        val data = i?.data ?: return
        if (data.scheme == "softalarm" && data.host == "paid") flow.checkNow()
    }

    private fun onPayBannerTap() {
        when (flow.phase) {
            Phase.WAITING, Phase.TIMED_OUT -> flow.checkNow()
            Phase.UNLOCKED, Phase.FAILED -> flow.reset()
            else -> {}
        }
    }

    // Shows what the payment journey is doing right now
    private fun showPayState(state: FlowState) {
        if (state.phase == Phase.IDLE) {
            payBanner.visibility = View.GONE
        } else {
            val hint = when (state.phase) {
                Phase.WAITING -> "\n(tap here once you have paid)"
                Phase.FAILED, Phase.UNLOCKED -> "\n(tap to dismiss)"
                else -> ""
            }
            payBanner.text = state.message + hint
            payBanner.visibility = View.VISIBLE
        }
        if (state.phase == Phase.UNLOCKED) {
            refreshProBanner()
            Toast.makeText(this, "Pro unlocked. Thank you!", Toast.LENGTH_LONG).show()
            handler.postDelayed({ if (flow.phase == Phase.UNLOCKED) flow.reset() }, 5000)
            maybeNudgeSignIn()
        }
    }

    // "My own file": the user picks any audio file from the phone (free for everyone)
    private fun pickFile(onPicked: (String) -> Unit) {
        pickedUriCallback = onPicked
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "audio/*"
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        try {
            startActivityForResult(i, REQ_PICK)
        } catch (e: Exception) {
            Toast.makeText(this, "No file picker found.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                // keep access so the alarm can play the file later, even after a restart
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                // some pickers do not allow it; the file may stop working after a restart
            }
            pickedUriCallback?.invoke(uri.toString())
        }
    }

    private fun displayName(uriStr: String): String {
        try {
            contentResolver.query(Uri.parse(uriStr), null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && idx >= 0) return c.getString(idx)
            }
        } catch (e: Exception) {
            // ignore
        }
        return "audio file"
    }

    // ------------------------------------------------------------------ Timer tab
    // The timer end time is saved, and a real alarm is set with AlarmManager,
    // so it still rings if the screen is off or the app is closed.

    private fun buildTimerPanel(): LinearLayout {
        val p = LinearLayout(this)
        p.orientation = LinearLayout.VERTICAL

        timerDisplay = bigDisplay("00:00:00")
        p.addView(timerDisplay, lp(MATCH, WRAP, 0, 22))

        timerSetup = LinearLayout(this)
        timerSetup.orientation = LinearLayout.HORIZONTAL
        timerSetup.gravity = Gravity.CENTER
        etHours = timeInput("0")
        etMinutes = timeInput("5")
        etSeconds = timeInput("0")
        val fields = listOf(etHours, etMinutes, etSeconds)
        val captions = listOf("Hours", "Minutes", "Seconds")
        for (i in fields.indices) {
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.gravity = Gravity.CENTER_HORIZONTAL
            col.addView(fields[i], LinearLayout.LayoutParams(dp(78), WRAP))
            val cap = smallCaption(captions[i])
            cap.textSize = 10.5f
            col.addView(cap, lp(WRAP, WRAP, 4, 0))
            val params = LinearLayout.LayoutParams(WRAP, WRAP)
            params.setMargins(dp(5), 0, dp(5), 0)
            timerSetup.addView(col, params)
        }
        p.addView(timerSetup, lp(MATCH, WRAP, 0, 20))

        val controls = LinearLayout(this)
        controls.orientation = LinearLayout.HORIZONTAL
        controls.gravity = Gravity.CENTER
        timerStartBtn = pillButton("Start", true)
        timerStartBtn.setOnClickListener { onTimerStart() }
        val reset = pillButton("Reset", false)
        reset.setOnClickListener { onTimerReset() }
        val bp = LinearLayout.LayoutParams(WRAP, WRAP)
        bp.setMargins(dp(6), 0, dp(6), 0)
        controls.addView(timerStartBtn, bp)
        controls.addView(reset, bp)
        p.addView(controls, lp(MATCH, WRAP))
        return p
    }

    private fun timeInput(start: String): EditText {
        val e = EditText(this)
        e.setText(start)
        e.inputType = InputType.TYPE_CLASS_NUMBER
        e.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(2))
        e.gravity = Gravity.CENTER
        e.textSize = 26f
        e.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        e.setTextColor(Colors.PLUM)
        e.setSelectAllOnFocus(true)
        e.background = rounded(Colors.GLASS, 14)
        e.setPadding(0, dp(10), 0, dp(10))
        return e
    }

    private fun timerEnd(): Long = prefs.getLong("timer_end", 0L)
    private fun timerPaused(): Long = prefs.getLong("timer_paused", 0L)

    private fun onTimerStart() {
        val end = timerEnd()
        val paused = timerPaused()
        val now = System.currentTimeMillis()
        if (end > now) {
            // running -> pause
            AlarmScheduler.cancelTimer(this)
            prefs.edit().putLong("timer_end", 0L).putLong("timer_paused", end - now).apply()
        } else if (paused > 0) {
            // paused -> resume
            val newEnd = now + paused
            prefs.edit().putLong("timer_end", newEnd).putLong("timer_paused", 0L).apply()
            AlarmScheduler.scheduleTimer(this, newEnd)
        } else {
            val h = etHours.text.toString().toLongOrNull() ?: 0L
            val m = etMinutes.text.toString().toLongOrNull() ?: 0L
            val s = etSeconds.text.toString().toLongOrNull() ?: 0L
            val totalMs = (h * 3600 + m * 60 + s) * 1000
            if (totalMs <= 0) {
                Toast.makeText(this, "Please set a time greater than zero.", Toast.LENGTH_SHORT).show()
                return
            }
            val newEnd = now + totalMs
            prefs.edit().putLong("timer_end", newEnd).putLong("timer_paused", 0L).apply()
            AlarmScheduler.scheduleTimer(this, newEnd)
        }
        refreshTimer()
    }

    private fun onTimerReset() {
        AlarmScheduler.cancelTimer(this)
        prefs.edit().putLong("timer_end", 0L).putLong("timer_paused", 0L).apply()
        refreshTimer()
    }

    private fun refreshTimer() {
        val end = timerEnd()
        val paused = timerPaused()
        val now = System.currentTimeMillis()
        if (end > 0 && end <= now) {
            // finished: the alarm service is already ringing
            prefs.edit().putLong("timer_end", 0L).apply()
            timerDisplay.text = formatHMS(0)
            timerSetup.visibility = View.VISIBLE
            timerStartBtn.text = "Start"
            return
        }
        when {
            end > now -> {
                timerDisplay.text = formatHMS((end - now + 999) / 1000)
                timerSetup.visibility = View.GONE
                timerStartBtn.text = "Pause"
            }
            paused > 0 -> {
                timerDisplay.text = formatHMS((paused + 999) / 1000)
                timerSetup.visibility = View.GONE
                timerStartBtn.text = "Resume"
            }
            else -> {
                timerDisplay.text = formatHMS(0)
                timerSetup.visibility = View.VISIBLE
                timerStartBtn.text = "Start"
            }
        }
    }

    // ------------------------------------------------------------------ Stopwatch tab

    private fun buildStopwatchPanel(): LinearLayout {
        val p = LinearLayout(this)
        p.orientation = LinearLayout.VERTICAL

        swDisplay = bigDisplay("00:00:00")
        p.addView(swDisplay, lp(MATCH, WRAP, 0, 22))

        val controls = LinearLayout(this)
        controls.orientation = LinearLayout.HORIZONTAL
        controls.gravity = Gravity.CENTER
        swStartBtn = pillButton("Start", true)
        swStartBtn.setOnClickListener { onSwStart() }
        val lap = pillButton("Lap", false)
        lap.setOnClickListener { onSwLap() }
        val reset = pillButton("Reset", false)
        reset.setOnClickListener { onSwReset() }
        val bp = LinearLayout.LayoutParams(WRAP, WRAP)
        bp.setMargins(dp(5), 0, dp(5), 0)
        controls.addView(swStartBtn, bp)
        controls.addView(lap, bp)
        controls.addView(reset, bp)
        p.addView(controls, lp(MATCH, WRAP))

        lapList = LinearLayout(this)
        lapList.orientation = LinearLayout.VERTICAL
        p.addView(lapList, lp(MATCH, WRAP, 18, 0))
        return p
    }

    private fun swElapsedMs(): Long {
        return swAccum + if (swRunning) SystemClock.elapsedRealtime() - swStartedAt else 0L
    }

    private fun updateStopwatch() {
        swDisplay.text = formatHMS(swElapsedMs() / 1000)
    }

    private fun onSwStart() {
        if (!swRunning) {
            swStartedAt = SystemClock.elapsedRealtime()
            swRunning = true
            swStartBtn.text = "Pause"
        } else {
            swAccum += SystemClock.elapsedRealtime() - swStartedAt
            swRunning = false
            swStartBtn.text = "Start"
        }
    }

    private fun onSwLap() {
        if (!swRunning) return
        lapCount++
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.background = rounded(Colors.GLASS, 12)
        row.setPadding(dp(16), dp(10), dp(16), dp(10))

        val name = TextView(this)
        name.text = "Lap $lapCount"
        name.textSize = 13f
        name.setTextColor(Colors.MAUVE)
        row.addView(name, LinearLayout.LayoutParams(0, WRAP, 1f))

        val time = TextView(this)
        time.text = formatHMS(swElapsedMs() / 1000)
        time.textSize = 14f
        time.typeface = Typeface.MONOSPACE
        time.setTextColor(Colors.PLUM)
        row.addView(time)

        lapList.addView(row, 0, lp(MATCH, WRAP, 0, 8))
    }

    private fun onSwReset() {
        swRunning = false
        swAccum = 0L
        swStartedAt = 0L
        lapCount = 0
        swStartBtn.text = "Start"
        lapList.removeAllViews()
        updateStopwatch()
    }
}
