package com.example.stereowidener

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.OpenableColumns
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.stereowidener.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(), PlaybackService.PlaybackStateListener {

    private lateinit var binding: ActivityMainBinding
    private var service: PlaybackService? = null
    private var bound = false
    private var currentUri: Uri? = null
    private var visualizer: Visualizer? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as PlaybackService.LocalBinder).getService()
            bound = true
            service?.stateListener = this@MainActivity
            val playing = service?.engine?.isCurrentlyPlaying() == true
            onPlaybackStateChanged(playing)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    private val pickAudio = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            currentUri = uri
            binding.tvTrackName.text = queryFileName(uri)
            binding.btnPlayPause.isEnabled = true
        }
    }

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* Visualizer / notification simply stay off if denied; playback still works. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) requestPermissions.launch(needed.toTypedArray())

        binding.btnPickFile.setOnClickListener {
            pickAudio.launch("audio/*")
        }

        binding.btnPlayPause.setOnClickListener {
            val svc = service ?: return@setOnClickListener
            if (svc.engine.isCurrentlyPlaying()) {
                svc.stopPlayback()
            } else {
                val uri = currentUri ?: return@setOnClickListener
                svc.engine.widthFactor = binding.sliderWidth.value / 100f
                ContextCompat.startForegroundService(this, Intent(this, PlaybackService::class.java))
                svc.play(uri, queryFileName(uri))
                startVisualizer()
            }
        }

        binding.sliderWidth.addOnChangeListener { _, value, _ ->
            binding.tvWidthLabel.text = "${value.toInt()}%"
            service?.engine?.widthFactor = value / 100f
        }

        binding.eqView.onBandChanged = { index, gainDb ->
            service?.engine?.equalizer?.setBandGain(index, gainDb)
        }

        binding.btnResetEq.setOnClickListener {
            service?.engine?.equalizer?.resetAll()
            for (i in 0 until EqualizerEngine.BAND_COUNT) {
                binding.eqView.setGain(i, 0f)
            }
        }

        val styles = VisualizerStyle.values()
        binding.spinnerVisualizerStyle.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, styles.map { it.label }
        )
        binding.spinnerVisualizerStyle.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                binding.spectrumView.style = styles[position]
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Bind (does not start foreground) so the UI can reach a running/prior service instance.
        bindService(Intent(this, PlaybackService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        runOnUiThread {
            binding.btnPlayPause.text = if (isPlaying) "Stop" else "Play"
            if (!isPlaying) stopVisualizer()
        }
    }

    private fun startVisualizer() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val engine = service?.engine ?: return
        stopVisualizer()
        try {
            visualizer = Visualizer(engine.audioSessionId()).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1]
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {
                        waveform?.let { runOnUiThread { binding.spectrumView.updateWaveform(it) } }
                    }
                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                        fft?.let { runOnUiThread { binding.spectrumView.updateFft(it) } }
                    }
                }, Visualizer.getMaxCaptureRate() / 2, true, true)
                enabled = true
            }
        } catch (e: Exception) {
            // Some devices/session states can reject Visualizer attach; visualization
            // simply won't show, playback is unaffected.
        }
    }

    private fun stopVisualizer() {
        visualizer?.release()
        visualizer = null
    }

    private fun queryFileName(uri: Uri): String {
        var name = "Selected track"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                name = cursor.getString(nameIndex)
            }
        }
        return name
    }

    override fun onResume() {
        super.onResume()
        // If playback is already running in the background (e.g. we relaunched
        // the app while music kept playing), re-attach the visualizer.
        if (service?.engine?.isCurrentlyPlaying() == true && visualizer == null) {
            startVisualizer()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVisualizer()
        if (bound) {
            service?.stateListener = null
            unbindService(connection)
            bound = false
        }
        // Deliberately NOT stopping playback here - that's the whole point of
        // the foreground service: audio keeps playing after this Activity is
        // gone. Playback only stops via the Play/Stop button or the
        // notification's Stop action, both of which call stopPlayback().
    }
}
