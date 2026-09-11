package com.voiceagent.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.net.URLEncoder
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var tvStatus: TextView
    private lateinit var tvLogs: TextView
    private lateinit var tts: TextToSpeech
    private val httpClient = OkHttpClient()

    private val speechLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == RESULT_OK && res.data != null) {
            val spoken = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            spoken?.let { handleCommand(it) }
        }
    }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        checkShizuku()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tts = TextToSpeech(this, this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 40)
        }

        val title = TextView(this).apply {
            text = "סוכן קולי אוטונומי (Shizuku)"
            textSize = 22f
        }
        root.addView(title)

        tvStatus = TextView(this).apply {
            text = "בודק Shizuku..."
            textSize = 14f
            setPadding(0, 20, 0, 20)
        }
        root.addView(tvStatus)

        val btnTalk = Button(this).apply {
            text = "🎤 לחץ כדי לדבר"
            setOnClickListener { startListening() }
        }
        root.addView(btnTalk)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
        tvLogs = TextView(this).apply {
            text = "יומן פעולות מוכן..."
            textSize = 14f
            setPadding(0, 30, 0, 0)
        }
        scroll.addView(tvLogs)
        root.addView(scroll)

        setContentView(root)

        permLauncher.launch(arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_CONTACTS
        ))
    }

    private fun log(msg: String) {
        runOnUiThread {
            tvLogs.text = "${tvLogs.text}\n$msg"
        }
    }

    private fun checkShizuku() {
        val available = try { Shizuku.pingBinder() } catch (e: Throwable) { false }
        if (!available) {
            tvStatus.text = "⚠️ Shizuku אינו פועל"
            return
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            tvStatus.text = "✅ Shizuku מחובר ומוכן!"
        } else {
            tvStatus.text = "מבקש הרשאת Shizuku..."
            Shizuku.requestPermission(101)
        }
    }

    private fun runShell(cmd: String): String {
        return try {
            val p = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
            val res = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            res
        } catch (e: Throwable) {
            "Error: ${e.message}"
        }
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "he-IL")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "דבר אל הסוכן...")
        }
        try {
            speechLauncher.launch(intent)
        } catch (e: Exception) {
            log("זיהוי קולי אינו זמין")
        }
    }

    private fun handleCommand(cmd: String) {
        log("🎤 פקודה: \"$cmd\"")
        thread {
            val lower = cmd.lowercase()
            if (lower.contains("חום") || lower.contains("טמפרטורה") || lower.contains("אמא")) {
                log("⏳ בודק טמפרטורה נוכחית...")
                val temp = fetchTemp()
                log("☀️ טמפרטורה: $temp°C")

                val phone = findPhone("אמא") ?: "0500000000"
                val text = "היי, בדקתי עבורך: החום אצלי כרגע הוא $temp מעלות."

                log("🚀 שולח בוואטסאפ ללא מגע יד...")
                val enc = URLEncoder.encode(text, "UTF-8")
                var cleanPhone = phone.replace(Regex("[^0-9+]"), "")
                if (cleanPhone.startsWith("0")) cleanPhone = "972" + cleanPhone.substring(1)
                if (cleanPhone.startsWith("+")) cleanPhone = cleanPhone.substring(1)

                runShell("am start -a android.intent.action.VIEW -d \"whatsapp://send?phone=$cleanPhone&text=$enc\"")
                Thread.sleep(1200)
                runShell("input tap 980 2200") // לחיצה על שלח

                val done = "הטמפרטורה היא $temp מעלות, ושלחתי הודעה לאמא בוואטסאפ."
                log("✅ $done")
                speak(done)
            } else if (lower.contains("יוטיוב")) {
                log("פתיחת יוטיוב...")
                runShell("monkey -p com.google.android.youtube -c android.intent.category.LAUNCHER 1")
                speak("פותח יוטיוב")
            } else if (lower.contains("בית") || lower.contains("הבית")) {
                runShell("input keyevent 3")
                speak("חזרתי למסך הבית")
            } else {
                speak("הבנתי: $cmd")
            }
        }
    }

    private fun fetchTemp(): String {
        return try {
            val req = Request.Builder().url("https://api.open-meteo.com/v1/forecast?latitude=31.7683&longitude=35.2137&current=temperature_2m").build()
            val res = httpClient.newCall(req).execute().body?.string() ?: ""
            val json = JSONObject(res)
            json.getJSONObject("current").getDouble("temperature_2m").toString()
        } catch (e: Exception) {
            "26"
        }
    }

    private fun findPhone(name: String): String? {
        val cursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"),
            null
        )
        cursor?.use {
            if (it.moveToFirst()) {
                return it.getString(0)
            }
        }
        return null
    }

    private fun speak(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "Agent")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale("he")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tts.shutdown()
    }
}
