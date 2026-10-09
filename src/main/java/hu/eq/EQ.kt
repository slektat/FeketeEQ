package hu.eq

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("eq", Context.MODE_PRIVATE)
    var on: Boolean
        get() = sp.getBoolean("on", false)
        set(v) { sp.edit().putBoolean("on", v).apply() }
    var bass: Int
        get() = sp.getInt("bass", 0)
        set(v) { sp.edit().putInt("bass", v).apply() }
    var virt: Int
        get() = sp.getInt("virt", 0)
        set(v) { sp.edit().putInt("virt", v).apply() }
    var loud: Int
        get() = sp.getInt("loud", 0)
        set(v) { sp.edit().putInt("loud", v).apply() }
    var status: String
        get() = sp.getString("status", "") ?: ""
        set(v) { sp.edit().putString("status", v).apply() }
    fun bandProgress(i: Int) = sp.getInt("b$i", 24)
    fun setBand(i: Int, v: Int) { sp.edit().putInt("b$i", v).apply() }
    fun bandDb(i: Int) = (bandProgress(i) - 24) * 0.5f
}

class EffectChain(private val session: Int) {
    companion object {
        val CUT = floatArrayOf(50f, 100f, 200f, 400f, 800f, 1600f, 3200f, 6400f, 12800f, 20000f)
    }
    private var dp: DynamicsProcessing? = null
    private var bb: BassBoost? = null
    private var vi: Virtualizer? = null
    private var le: LoudnessEnhancer? = null
    val report = StringBuilder()

    private fun <T> tryMake(name: String, f: () -> T): T? = try {
        f().also { report.append("$name: OK  ") }
    } catch (e: Throwable) {
        report.append("$name: nem elérhető  ")
        null
    }

    fun create() {
        dp = tryMake("10 sávos EQ") {
            val cfg = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                2, true, 10, false, 0, false, 0, false
            ).build()
            DynamicsProcessing(0, session, cfg).also { it.setEnabled(true) }
        }
        bb = tryMake("Bass boost") { BassBoost(0, session).also { it.setEnabled(true) } }
        vi = tryMake("3D") { Virtualizer(0, session).also { it.setEnabled(true) } }
        le = tryMake("Hangerőemelés") { LoudnessEnhancer(session).also { it.setEnabled(true) } }
    }

    fun apply(p: Prefs) {
        try {
            dp?.let { d ->
                var maxPos = 0f
                for (i in 0 until 10) {
                    val g = p.bandDb(i)
                    if (g > maxPos) maxPos = g
                    d.setPreEqBandAllChannelsTo(i, DynamicsProcessing.EqBand(true, CUT[i], g))
                }
                d.setInputGainAllChannelsTo(-maxPos)
            }
        } catch (_: Throwable) {}
        try { bb?.setStrength(p.bass.toShort()) } catch (_: Throwable) {}
        try { vi?.setStrength(p.virt.toShort()) } catch (_: Throwable) {}
        try { le?.setTargetGain(p.loud * 100) } catch (_: Throwable) {}
    }

    fun release() {
        try { dp?.release() } catch (_: Throwable) {}
        try { bb?.release() } catch (_: Throwable) {}
        try { vi?.release() } catch (_: Throwable) {}
        try { le?.release() } catch (_: Throwable) {}
    }
}

class EqService : Service() {
    companion object { const val ACTION_APPLY = "hu.eq.APPLY" }

    private val chains = HashMap<Int, EffectChain>()
    private lateinit var p: Prefs

    private val rx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val s = i.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
            if (s <= 0) return
            when (i.action) {
                AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> add(s)
                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> {
                    chains.remove(s)?.release()
                }
            }
        }
    }

    private fun add(s: Int) {
        if (chains.containsKey(s)) return
        val c = EffectChain(s)
        c.create()
        c.apply(p)
        chains[s] = c
        if (s == 0) p.status = c.report.toString().trim()
    }

    override fun onCreate() {
        super.onCreate()
        p = Prefs(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("eq", "Equalizer", NotificationManager.IMPORTANCE_LOW)
        )
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "eq")
            .setContentTitle("Fekete EQ aktív")
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(1, n)
        }
        val f = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(rx, f, Context.RECEIVER_EXPORTED)
        else registerReceiver(rx, f)
        add(0)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_APPLY) chains.values.forEach { it.apply(p) }
        return START_STICKY
    }

    override fun onDestroy() {
        try { unregisterReceiver(rx) } catch (_: Throwable) {}
        chains.values.forEach { it.release() }
        chains.clear()
        super.onDestroy()
    }

    override fun onBind(i: Intent?): IBinder? = null
}

class MainActivity : Activity() {
    private lateinit var p: Prefs
    private lateinit var status: TextView
    private val bands = ArrayList<SeekBar>()
    private lateinit var bassBar: SeekBar
    private lateinit var virtBar: SeekBar
    private lateinit var loudBar: SeekBar
    private val accent = ColorStateList.valueOf(Color.parseColor("#4FC3F7"))

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun svc(action: String? = null) =
        Intent(this, EqService::class.java).also { it.action = action }

    private fun push() { if (p.on) startService(svc(EqService.ACTION_APPLY)) }

    private fun refresh() {
        status.postDelayed({
            status.text = if (p.on) p.status else "Kikapcsolva"
        }, 700)
    }

    private fun label(t: String, size: Float = 14f, color: Int = Color.WHITE) =
        TextView(this).apply { text = t; textSize = size; setTextColor(color) }

    private fun row(
        parent: LinearLayout, title: String, max: Int, start: Int,
        fmt: (Int) -> String, save: (Int) -> Unit
    ): SeekBar {
        val l = label("$title: ${fmt(start)}")
        l.setPadding(0, dp(10), 0, 0)
        val bar = SeekBar(this).apply {
            this.max = max
            progress = start
            progressTintList = accent
            thumbTintList = accent
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, v: Int, user: Boolean) {
                    l.text = "$title: ${fmt(v)}"
                    if (user) { save(v); push() }
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        parent.addView(l)
        parent.addView(bar)
        return bar
    }

    private fun preset(db: IntArray, bass: Int, virt: Int, loud: Int) {
        for (i in 0..9) {
            val v = db[i] * 2 + 24
            bands[i].progress = v
            p.setBand(i, v)
        }
        bassBar.progress = bass; p.bass = bass
        virtBar.progress = virt; p.virt = virt
        loudBar.progress = loud; p.loud = loud
        push()
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        p = Prefs(this)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(48), dp(16), dp(48))
            setBackgroundColor(Color.BLACK)
        }

        status = label("", 12f, Color.parseColor("#9E9E9E"))

        val sw = Switch(this).apply {
            text = "Equalizer bekapcsolva"
            textSize = 18f
            setTextColor(Color.WHITE)
            isChecked = p.on
            setOnCheckedChangeListener { _, c ->
                p.on = c
                if (c) startForegroundService(svc()) else stopService(svc())
                refresh()
            }
        }
        root.addView(sw)
        root.addView(status)

        val presets = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(t: String, a: () -> Unit) = Button(this).apply {
            text = t
            setOnClickListener { a() }
        }
        presets.addView(btn("Lapos") { preset(IntArray(10), 0, 0, 0) })
        presets.addView(btn("Basszus") {
            preset(intArrayOf(8, 6, 3, 1, 0, 0, 0, 0, 0, 0), 400, 0, 0)
        })
        presets.addView(btn("Hangos") {
            preset(intArrayOf(8, 6, 3, 1, 0, 0, 1, 1, 1, 0), 400, 300, 8)
        })
        root.addView(presets)

        root.addView(label("10 sávos EQ", 16f).apply { setPadding(0, dp(16), 0, 0) })
        for (i in 0..9) {
            val hz = EffectChain.CUT[i]
            val name = if (hz >= 1000f) "${hz / 1000f} kHz" else "${hz.toInt()} Hz"
            bands.add(row(root, if (i == 0) "≤ $name" else name, 48, p.bandProgress(i),
                { v -> String.format("%+.1f dB", (v - 24) * 0.5f) },
                { v -> p.setBand(i, v) }))
        }

        root.addView(label("Effektek", 16f).apply { setPadding(0, dp(20), 0, 0) })
        bassBar = row(root, "Bass boost", 1000, p.bass, { v -> "${v / 10} %" }, { p.bass = it })
        virtBar = row(root, "3D térhangzás", 1000, p.virt, { v -> "${v / 10} %" }, { p.virt = it })
        loudBar = row(root, "Hangerőemelés", 15, p.loud, { v -> "+$v dB" }, { p.loud = it })

        root.addView(
            label(
                "Figyelem: a hangerőemelés a gyári EU-s hallásvédelmi limitet kerüli meg. " +
                "Tartósan nagy hangerőn árthat a hallásodnak, és torzíthat.",
                12f, Color.parseColor("#FF8A65")
            ).apply { setPadding(0, dp(20), 0, 0) }
        )

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(root)
        })
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }
}
