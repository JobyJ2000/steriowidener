package com.example.stereowidener

import android.content.Context
import android.media.*
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * Decodes a compressed audio file (mp3/aac/wav/flac/ogg - whatever the device's
 * MediaCodec supports), applies mid-side stereo widening in real time on the raw
 * PCM samples, and streams the result to the speakers via AudioTrack.
 *
 * Mid-side widening:
 *   mid  = (L + R) / 2      -> the mono/common content
 *   side = (L - R) / 2      -> the stereo difference content
 *   L' = mid + side * width
 *   R' = mid - side * width
 *
 * width = 1.0 -> unchanged. width > 1.0 -> wider image. width = 0 -> mono.
 */
class AudioWidenerEngine(private val context: Context) {

    @Volatile
    var widthFactor: Float = 1.5f

    @Volatile
    private var isPlaying = false

    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var audioTrack: AudioTrack? = null
    private var playThread: Thread? = null

    val equalizer = EqualizerEngine(44100)

    var onCompletion: (() -> Unit)? = null

    /** Valid only once play() has started; used to attach a Visualizer for the spectrum view. */
    fun audioSessionId(): Int = audioTrack?.audioSessionId ?: 0

    fun play(uri: Uri) {
        stop()

        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        this.extractor = extractor

        val trackIndex = selectAudioTrack(extractor)
            ?: throw IllegalArgumentException("No audio track found in file")
        extractor.selectTrack(trackIndex)

        val format = extractor.getTrackFormat(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()
        this.codec = codec

        val channelConfig =
            if (channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBufSize = AudioTrack.getMinBufferSize(
            sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .build()
            )
            .setBufferSizeInBytes(minBufSize * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        this.audioTrack = track
        equalizer.setSampleRate(sampleRate)

        isPlaying = true
        track.play()

        playThread = thread(start = true) {
            decodeLoop(channelCount)
        }
    }

    private fun decodeLoop(channelCount: Int) {
        val extractor = this.extractor ?: return
        val codec = this.codec ?: return
        val audioTrack = this.audioTrack ?: return

        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        try {
            while (isPlaying && !outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inIndex)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(
                                inIndex, 0, sampleSize, extractor.sampleTime, 0
                            )
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outIndex)!!
                    val chunk = ByteArray(bufferInfo.size)
                    outputBuffer.get(chunk)
                    outputBuffer.clear()

                    if (channelCount == 2 && chunk.isNotEmpty()) {
                        applyStereoWidening(chunk, widthFactor)
                        equalizer.process(chunk)
                    }

                    if (chunk.isNotEmpty()) {
                        audioTrack.write(chunk, 0, chunk.size)
                    }
                    codec.releaseOutputBuffer(outIndex, false)

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                }
            }
        } catch (e: Exception) {
            // Playback ended abnormally (e.g. stop() was called and released resources mid-loop)
        } finally {
            val wasPlaying = isPlaying
            isPlaying = false
            releaseInternal()
            if (wasPlaying) onCompletion?.invoke()
        }
    }

    /** In-place mid-side widening on interleaved 16-bit stereo PCM. */
    private fun applyStereoWidening(pcm: ByteArray, width: Float) {
        val buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        val shortBuffer = buffer.asShortBuffer()
        val n = shortBuffer.limit()
        var i = 0
        while (i + 1 < n) {
            val l = shortBuffer.get(i).toFloat()
            val r = shortBuffer.get(i + 1).toFloat()
            val mid = (l + r) * 0.5f
            val side = (l - r) * 0.5f * width
            val newL = (mid + side).coerceIn(-32768f, 32767f)
            val newR = (mid - side).coerceIn(-32768f, 32767f)
            shortBuffer.put(i, newL.toInt().toShort())
            shortBuffer.put(i + 1, newR.toInt().toShort())
            i += 2
        }
    }

    private fun selectAudioTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return null
    }

    fun stop() {
        isPlaying = false
        playThread?.join(500)
        playThread = null
        releaseInternal()
    }

    private fun releaseInternal() {
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        try { extractor?.release() } catch (_: Exception) {}
        try { audioTrack?.stop() } catch (_: Exception) {}
        try { audioTrack?.release() } catch (_: Exception) {}
        codec = null
        extractor = null
        audioTrack = null
    }

    fun isCurrentlyPlaying(): Boolean = isPlaying
}
