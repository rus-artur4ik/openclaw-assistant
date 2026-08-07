package com.openclaw.assistant.talk

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Streams PCM16 chunks to the speaker with a barge-in-safe flush.
 *
 * The gateway pushes 200 ms chunks and expects `clear` to silence playback immediately — anything
 * already handed to AudioTrack has to be dropped, not drained, or the assistant keeps talking over
 * the user after a barge-in.
 */
class PcmStreamPlayer(
  private val sampleRateHz: Int,
  private val scope: CoroutineScope,
  /**
   * Called when the current utterance has finished being *heard*: the gateway said it sent
   * everything, the queue is empty, and AudioTrack has rendered what it held.
   */
  private val onUtteranceFinished: () -> Unit = {},
) {
  companion object {
    private const val TAG = "PcmStreamPlayer"
    private const val BYTES_PER_FRAME = 2

    /**
     * Writes are sliced so a barge-in cannot be stuck behind a whole 200 ms chunk already inside
     * a blocking [AudioTrack.write].
     */
    private const val WRITE_SLICE_MS = 20
  }

  private data class Chunk(val generation: Int, val pcm: ByteArray)

  private val queue = Channel<Chunk>(capacity = Channel.UNLIMITED)
  private val generation = AtomicInteger(0)

  /** Chunks queued or being written. Touched from the gateway frame pump and the writer thread. */
  private val pending = AtomicInteger(0)

  /** Frames handed to AudioTrack since the last [flush]; paired with its playback head position. */
  private val framesWritten = AtomicLong(0)

  /**
   * True once the gateway said the utterance is fully sent.
   *
   * Without this, an empty queue means nothing: it also happens whenever the writer catches up with
   * a jittery downlink mid-answer, which would flip the UI out of SPEAKING while the user is still
   * being spoken to.
   */
  private val utteranceComplete = AtomicBoolean(false)

  private var track: AudioTrack? = null
  private var pump: Job? = null

  /** True while audio is queued or being written. */
  val isPlaying: Boolean
    get() = pending.get() > 0

  fun start() {
    if (track != null) return
    val minBuffer =
      AudioTrack.getMinBufferSize(sampleRateHz, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
    if (minBuffer <= 0) {
      throw IllegalStateException("AudioTrack rejected ${sampleRateHz}Hz (getMinBufferSize=$minBuffer)")
    }
    val created =
      AudioTrack.Builder()
        .setAudioAttributes(
          AudioAttributes.Builder()
            // VOICE_COMMUNICATION keeps playback on the same stream the AEC uses as its reference,
            // which is what makes full-duplex barge-in survivable on a speakerphone.
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        )
        .setAudioFormat(
          AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRateHz)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        )
        .setBufferSizeInBytes(minBuffer * 2)
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()
    track = created
    created.play()
    pump = scope.launch(Dispatchers.IO) {
      for (chunk in queue) {
        writeChunk(chunk)
        onChunkDone()
      }
    }
  }

  fun enqueue(pcm: ByteArray) {
    if (pcm.isEmpty() || track == null) return
    utteranceComplete.set(false)
    pending.incrementAndGet()
    if (queue.trySend(Chunk(generation.get(), pcm)).isFailure) {
      pending.decrementAndGet()
    }
  }

  /** The gateway sent the last chunk of this utterance; playback may now be declared finished. */
  fun markUtteranceComplete() {
    utteranceComplete.set(true)
    if (pending.get() == 0) scheduleFinishedCallback()
  }

  /** Drops everything queued and everything already inside AudioTrack. */
  fun flush() {
    generation.incrementAndGet()
    pending.set(0)
    utteranceComplete.set(false)
    while (queue.tryReceive().isSuccess) {
      // drain
    }
    val active = track ?: return
    runCatching {
      // pause() also releases a writer blocked mid-chunk, so it cannot keep feeding the cancelled
      // utterance past the flush.
      active.pause()
      active.flush()
      framesWritten.set(headPosition(active))
      active.play()
    }
  }

  fun release() {
    queue.close()
    pump?.cancel()
    pump = null
    pending.set(0)
    val active = track ?: return
    track = null
    runCatching { active.pause() }
    runCatching { active.flush() }
    runCatching { active.stop() }
    runCatching { active.release() }
  }

  private fun writeChunk(chunk: Chunk) {
    if (chunk.generation != generation.get()) return
    val sliceBytes = maxOf(BYTES_PER_FRAME, sampleRateHz * WRITE_SLICE_MS / 1000 * BYTES_PER_FRAME)
    var offset = 0
    while (offset < chunk.pcm.size) {
      // Re-checked per slice: a flush() between slices must abandon the rest of the chunk.
      if (chunk.generation != generation.get()) return
      val active = track ?: return
      val length = minOf(sliceBytes, chunk.pcm.size - offset)
      val written =
        try {
          active.write(chunk.pcm, offset, length)
        } catch (err: Throwable) {
          Log.w(TAG, "AudioTrack write failed: ${err.message}")
          return
        }
      if (written <= 0) return
      framesWritten.addAndGet((written / BYTES_PER_FRAME).toLong())
      offset += written
    }
  }

  private fun onChunkDone() {
    val remaining = pending.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
    if (remaining == 0 && utteranceComplete.get()) scheduleFinishedCallback()
  }

  private fun scheduleFinishedCallback() {
    val generationAtDrain = generation.get()
    scope.launch(Dispatchers.IO) {
      // Let the track render what it already holds before declaring the answer over; write() returns
      // when the data is copied into the buffer, not when it has been heard.
      delay(remainingPlaybackMs())
      if (generation.get() == generationAtDrain && pending.get() == 0 && utteranceComplete.get()) {
        onUtteranceFinished()
      }
    }
  }

  private fun remainingPlaybackMs(): Long {
    val active = track ?: return 0
    val remainingFrames = (framesWritten.get() - headPosition(active)).coerceAtLeast(0)
    return remainingFrames * 1000 / sampleRateHz
  }

  /** [AudioTrack.getPlaybackHeadPosition] returns a wrapping unsigned 32-bit frame count. */
  private fun headPosition(active: AudioTrack): Long =
    runCatching { active.playbackHeadPosition.toLong() and 0xFFFFFFFFL }.getOrDefault(0L)
}
