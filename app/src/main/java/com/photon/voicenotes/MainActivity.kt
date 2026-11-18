package com.photon.voicenotes

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private enum class State { LOADING, IDLE, RECORDING, WORKING, FAILED }

    private val worker = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private val recorder = Recorder()
    private lateinit var store: NotesStore
    private var whisper: Whisper? = null
    private var state = State.LOADING
    private var recordStart = 0L
    private var currentNote: Note? = null

    private lateinit var status: TextView
    private lateinit var timer: TextView
    private lateinit var recordBtn: MaterialButton
    private lateinit var langGroup: MaterialButtonToggleGroup
    private lateinit var progress: LinearProgressIndicator
    private lateinit var result: EditText
    private lateinit var historyList: ListView
    private lateinit var historyAdapter: ArrayAdapter<String>

    private val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
    private val dateFmt = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        timer = findViewById(R.id.timer)
        recordBtn = findViewById(R.id.recordBtn)
        langGroup = findViewById(R.id.langGroup)
        progress = findViewById(R.id.progress)
        result = findViewById(R.id.resultText)
        historyList = findViewById(R.id.historyList)

        store = NotesStore(this)
        historyAdapter = ArrayAdapter(this, R.layout.item_note, ArrayList())
        historyList.adapter = historyAdapter
        refreshHistory()

        val prefs = getPreferences(MODE_PRIVATE)
        langGroup.check(prefs.getInt("lang", R.id.langAuto))
        langGroup.addOnButtonCheckedListener { _, id, checked ->
            if (checked) prefs.edit().putInt("lang", id).apply()
        }

        recordBtn.setOnClickListener { onRecordTapped() }
        findViewById<MaterialButton>(R.id.copyBtn).setOnClickListener { copyText() }
        findViewById<MaterialButton>(R.id.shareBtn).setOnClickListener { shareText() }
        findViewById<MaterialButton>(R.id.clearBtn).setOnClickListener {
            saveEdits()
            currentNote = null
            result.setText("")
        }

        historyList.setOnItemClickListener { _, _, pos, _ ->
            saveEdits()
            val note = store.notes[pos]
            currentNote = note
            result.setText(note.text)
        }
        historyList.setOnItemLongClickListener { _, _, pos, _ ->
            val note = store.notes[pos]
            AlertDialog.Builder(this)
                .setTitle("Delete this note?")
                .setMessage(note.text.take(120))
                .setPositiveButton("Delete") { _, _ ->
                    if (currentNote == note) { currentNote = null; result.setText("") }
                    store.remove(note)
                    refreshHistory()
                }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }

        setState(State.LOADING)
        worker.execute {
            val w = Whisper.load(assets, MODEL_PATH)
            ui.post {
                whisper = w
                setState(if (w != null) State.IDLE else State.FAILED)
            }
        }
    }

    private fun selectedLanguage(): String = when (langGroup.checkedButtonId) {
        R.id.langEn -> "en"
        R.id.langBn -> "bn"
        R.id.langHi -> "hi"
        else -> "auto"
    }

    private fun onRecordTapped() {
        when (state) {
            State.IDLE -> startRecording()
            State.RECORDING -> stopAndTranscribe()
            else -> Unit
        }
    }

    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        if (!recorder.start()) {
            toast("Could not open the microphone")
            return
        }
        recordStart = SystemClock.elapsedRealtime()
        setState(State.RECORDING)
        ui.post(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (state != State.RECORDING) return
            val secs = ((SystemClock.elapsedRealtime() - recordStart) / 1000).toInt()
            timer.text = "● Recording  %d:%02d   (tap to stop)".format(secs / 60, secs % 60)
            if (secs >= MAX_SECONDS) stopAndTranscribe() else ui.postDelayed(this, 250)
        }
    }

    private fun stopAndTranscribe() {
        val audio = recorder.stop()
        if (audio.size < Recorder.RATE / 2) {
            toast("Too short — hold on a little longer")
            timer.text = "Tap the mic and speak"
            setState(State.IDLE)
            return
        }
        val lang = selectedLanguage()
        val w = whisper ?: return
        setState(State.WORKING)
        val seconds = audio.size / Recorder.RATE.toFloat()
        val started = SystemClock.elapsedRealtime()
        worker.execute {
            val r = w.transcribe(audio, lang, threads)
            val took = (SystemClock.elapsedRealtime() - started) / 1000f
            ui.post {
                setState(State.IDLE)
                if (r.text.isBlank()) {
                    timer.text = "Couldn't hear any words. Try again."
                    return@post
                }
                saveEdits()
                val note = Note(System.currentTimeMillis(), r.language, r.text)
                store.add(note)
                currentNote = note
                result.setText(r.text)
                refreshHistory()
                timer.text = "%.0fs of speech written in %.1fs · %s".format(
                    seconds, took, languageName(r.language)
                )
            }
        }
    }

    private fun setState(s: State) {
        state = s
        val keepOn = s == State.RECORDING || s == State.WORKING
        if (keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        progress.visibility = if (s == State.WORKING || s == State.LOADING) View.VISIBLE else View.GONE
        recordBtn.isEnabled = s == State.IDLE || s == State.RECORDING
        for (i in 0 until langGroup.childCount) langGroup.getChildAt(i).isEnabled = s == State.IDLE

        val red = ContextCompat.getColor(this, R.color.record_red)
        val primary = ContextCompat.getColor(this, R.color.brand)
        recordBtn.backgroundTintList = ColorStateList.valueOf(if (s == State.RECORDING) red else primary)
        recordBtn.setIconResource(if (s == State.RECORDING) R.drawable.ic_stop else R.drawable.ic_mic)

        when (s) {
            State.LOADING -> { status.text = "Loading speech engine…"; timer.text = "" }
            State.IDLE -> status.text = "Ready · works fully offline"
            State.RECORDING -> status.text = "Listening…"
            State.WORKING -> { status.text = "Writing your words…"; timer.text = "Please wait, this takes a few seconds" }
            State.FAILED -> { status.text = "Speech engine failed to load"; timer.text = "Please reinstall the app" }
        }
        if (s == State.IDLE && timer.text.isNullOrEmpty()) timer.text = "Tap the mic and speak"
    }

    private fun saveEdits() {
        val note = currentNote ?: return
        val text = result.text.toString()
        if (text != note.text && text.isNotBlank()) {
            note.text = text
            store.save()
            refreshHistory()
        }
    }

    private fun refreshHistory() {
        historyAdapter.clear()
        historyAdapter.addAll(store.notes.map { n ->
            val preview = n.text.replace('\n', ' ').let { if (it.length > 90) it.take(90) + "…" else it }
            "${dateFmt.format(Date(n.id))} · ${languageName(n.language)}\n$preview"
        })
    }

    private fun copyText() {
        val text = result.text.toString()
        if (text.isBlank()) return toast("Nothing to copy yet")
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Voice note", text))
        toast("Copied")
    }

    private fun shareText() {
        val text = result.text.toString()
        if (text.isBlank()) return toast("Nothing to share yet")
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, "Share note"))
    }

    private fun languageName(code: String) = when (code) {
        "en" -> "English"
        "bn" -> "বাংলা"
        "hi" -> "हिंदी"
        "" -> "—"
        else -> code
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) startRecording()
        else toast("Microphone permission is needed to listen")
    }

    override fun onPause() {
        super.onPause()
        saveEdits()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (state == State.RECORDING) recorder.stop()
        worker.execute { whisper?.release() }
        worker.shutdown()
    }

    companion object {
        const val MODEL_PATH = "models/ggml-base.bin"
        const val MAX_SECONDS = 600
    }
}
