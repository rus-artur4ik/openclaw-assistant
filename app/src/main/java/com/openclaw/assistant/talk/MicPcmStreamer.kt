package com.openclaw.assistant.talk

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log

/**
 * Captures mono PCM16 at a fixed rate and hands it over in short chunks.
 *
 * The gateway's STT cascade decimates the uplink by exactly 3 to reach 8 kHz, so it only accepts the
 * sample rate it advertised in `talk.session.create` — usually 24 kHz. Not every device can open
 * AudioRecord at 24 kHz, so we fall back to capturing at 48 kHz and decimating by 2 in software.
 *
 * Hardware AEC is requested when available: the relay keeps the microphone open while the answer
 * plays, and without echo cancellation the server-side VAD hears the assistant and barges in on
 * itself.
 */
class MicPcmStreamer(
  private val targetSampleRateHz: Int,
  /** Chunk size handed to [start]'s callback, in milliseconds of audio. */
  private val chunkMs: Int = 40,
) {
  companion object {
    private const val TAG = "MicPcmStreamer"
    private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
    private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

    /** 3 PCM16 samples — the group size the gateway's 3:1 downsample consumes without remainder. */
    internal const val FRAME_GROUP_BYTES = 6

    /**
     * Splits a decimated buffer into the part that can be sent now (a whole number of
     * [FRAME_GROUP_BYTES] groups, prefixed with any leftover) and the new leftover.
     *
     * Extracted for unit testing: the gateway drops a partial group silently, so an off-by-one here
     * shows up only as gradually degrading recognition.
     */
    internal fun alignToFrameGroups(carry: ByteArray, incoming: ByteArray): Pair<ByteArray, ByteArray> {
      val total = carry.size + incoming.size
      val emit = total - (total % FRAME_GROUP_BYTES)
      if (emit <= 0) return ByteArray(0) to (carry + incoming)
      val combined = carry + incoming
      return combined.copyOfRange(0, emit) to combined.copyOfRange(emit, combined.size)
    }
  }

  class UnavailableException(message: String) : Exception(message)

  private var record: AudioRecord? = null
  private var aec: AcousticEchoCanceler? = null
  private var ns: NoiseSuppressor? = null
  private var agc: AutomaticGainControl? = null

  @Volatile private var running = false

  /** True when the platform confirmed hardware echo cancellation for this capture session. */
  @Volatile var echoCancellationEnabled = false
    private set

  /** Capture rate actually opened; equals [targetSampleRateHz] unless software decimation kicked in. */
  @Volatile var captureSampleRateHz = 0
    private set

  /**
   * Opens the microphone and blocks the calling thread, invoking [onChunk] with little-endian PCM16
   * at [targetSampleRateHz] until [stop] is called or the thread is interrupted.
   *
   * Must be called off the main thread. Requires RECORD_AUDIO; the caller checks that first.
   */
  @SuppressLint("MissingPermission")
  fun run(onChunk: (ByteArray) -> Unit, onLevel: (Float) -> Unit = {}) {
    val (rate, decimation) = resolveCaptureRate()
    captureSampleRateHz = rate

    val minBuffer = AudioRecord.getMinBufferSize(rate, CHANNEL, ENCODING)
    // [chunkMs] describes the emitted chunk, so the read size is expressed in target frames and
    // scaled by the decimation factor — otherwise a 48 kHz fallback would silently double it.
    val framesPerChunk = targetSampleRateHz * chunkMs / 1000
    val samplesPerRead = framesPerChunk * decimation
    // Four chunks of headroom: enough to ride out a GC pause without dropping input.
    val bufferSize = maxOf(minBuffer, samplesPerRead * 2 * 4)

    val audioRecord =
      try {
        AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, CHANNEL, ENCODING, bufferSize)
      } catch (err: Throwable) {
        throw UnavailableException("AudioRecord creation failed: ${err.message}")
      }
    if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
      audioRecord.release()
      throw UnavailableException("AudioRecord failed to initialize — microphone may be in use")
    }
    record = audioRecord
    attachEffects(audioRecord.audioSessionId)

    val readBuffer = ShortArray(samplesPerRead)
    // The gateway downsamples 3:1 with floor(bytes/2/3) and no carry between chunks, so anything
    // that is not a whole number of 3-sample groups is discarded server-side. Keep every emitted
    // chunk a multiple of 6 bytes and carry the remainder into the next one.
    var carry = ByteArray(0)
    running = true
    try {
      audioRecord.startRecording()
      if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
        throw UnavailableException("AudioRecord did not start — microphone may be in use")
      }
      while (running && !Thread.currentThread().isInterrupted) {
        val read = audioRecord.read(readBuffer, 0, readBuffer.size)
        if (read == 0) continue
        if (read < 0) {
          // Every negative return is terminal — ERROR, ERROR_DEAD_OBJECT and the rest never recover,
          // and treating them as "try again" busy-spins the capture thread forever.
          throw UnavailableException("AudioRecord read failed ($read)")
        }
        val outFrames = read / decimation
        if (outFrames <= 0) continue
        val decimated = ByteArray(outFrames * 2)
        var peak = 0
        for (i in 0 until outFrames) {
          var sum = 0
          for (j in 0 until decimation) sum += readBuffer[i * decimation + j].toInt()
          val sample = (sum / decimation).coerceIn(-32768, 32767)
          val abs = if (sample < 0) -sample else sample
          if (abs > peak) peak = abs
          decimated[i * 2] = (sample and 0xFF).toByte()
          decimated[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        onLevel(peak / 32768f)

        val (aligned, leftover) = alignToFrameGroups(carry, decimated)
        carry = leftover
        if (aligned.isNotEmpty()) onChunk(aligned)
      }
    } finally {
      running = false
      runCatching { audioRecord.stop() }
      releaseEffects()
      runCatching { audioRecord.release() }
      record = null
    }
  }

  fun stop() {
    running = false
  }

  /** Picks a capture rate the device supports, plus the integer decimation needed to reach the target. */
  private fun resolveCaptureRate(): Pair<Int, Int> {
    val candidates = listOf(targetSampleRateHz to 1, targetSampleRateHz * 2 to 2)
    for ((rate, decimation) in candidates) {
      val min = AudioRecord.getMinBufferSize(rate, CHANNEL, ENCODING)
      if (min > 0) return rate to decimation
      Log.w(TAG, "AudioRecord rejected ${rate}Hz (getMinBufferSize=$min)")
    }
    throw UnavailableException("No supported capture rate for ${targetSampleRateHz}Hz")
  }

  private fun attachEffects(audioSessionId: Int) {
    if (AcousticEchoCanceler.isAvailable()) {
      aec = runCatching { AcousticEchoCanceler.create(audioSessionId) }.getOrNull()
      echoCancellationEnabled = runCatching { aec?.setEnabled(true) == android.media.audiofx.AudioEffect.SUCCESS }
        .getOrDefault(false)
    }
    if (!echoCancellationEnabled) {
      Log.w(TAG, "No hardware AEC — the server VAD may hear the assistant's own speech")
    }
    if (NoiseSuppressor.isAvailable()) {
      ns = runCatching { NoiseSuppressor.create(audioSessionId)?.apply { enabled = true } }.getOrNull()
    }
    if (AutomaticGainControl.isAvailable()) {
      agc = runCatching { AutomaticGainControl.create(audioSessionId)?.apply { enabled = true } }.getOrNull()
    }
  }

  private fun releaseEffects() {
    runCatching { aec?.release() }
    runCatching { ns?.release() }
    runCatching { agc?.release() }
    aec = null
    ns = null
    agc = null
    echoCancellationEnabled = false
  }
}
