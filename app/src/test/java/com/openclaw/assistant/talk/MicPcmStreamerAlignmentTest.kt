package com.openclaw.assistant.talk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gateway's mu-law downsample consumes 3 samples (6 bytes) at a time with no carry between
 * appendAudio calls, so a partial group at the end of a chunk is discarded server-side. These tests
 * pin the alignment that prevents that silent loss.
 */
class MicPcmStreamerAlignmentTest {

  @Test
  fun `an already aligned buffer passes through untouched`() {
    val incoming = ByteArray(12) { it.toByte() }

    val (aligned, carry) = MicPcmStreamer.alignToFrameGroups(ByteArray(0), incoming)

    assertEquals(12, aligned.size)
    assertEquals(0, carry.size)
    assertTrue(incoming.contentEquals(aligned))
  }

  @Test
  fun `a partial group is held back instead of being sent and dropped`() {
    val incoming = ByteArray(8) { it.toByte() }

    val (aligned, carry) = MicPcmStreamer.alignToFrameGroups(ByteArray(0), incoming)

    assertEquals(6, aligned.size)
    assertEquals(2, carry.size)
    assertEquals(6.toByte(), carry[0])
    assertEquals(7.toByte(), carry[1])
  }

  @Test
  fun `carried bytes are prepended to the next chunk in order`() {
    val carryIn = byteArrayOf(6, 7)
    val incoming = byteArrayOf(8, 9, 10, 11)

    val (aligned, carryOut) = MicPcmStreamer.alignToFrameGroups(carryIn, incoming)

    assertTrue(byteArrayOf(6, 7, 8, 9, 10, 11).contentEquals(aligned))
    assertEquals(0, carryOut.size)
  }

  @Test
  fun `nothing is emitted until a whole group is available`() {
    val (aligned, carry) = MicPcmStreamer.alignToFrameGroups(byteArrayOf(1, 2), byteArrayOf(3, 4))

    assertEquals(0, aligned.size)
    assertTrue(byteArrayOf(1, 2, 3, 4).contentEquals(carry))
  }

  @Test
  fun `no sample is lost across a long ragged stream`() {
    var carry = ByteArray(0)
    val emitted = ArrayList<Byte>()
    var produced = 0
    // Deliberately ragged read sizes — AudioRecord returns short reads in practice.
    for (size in listOf(7, 5, 11, 3, 8, 2, 13, 4)) {
      val chunk = ByteArray(size) { (produced + it).toByte() }
      produced += size
      val (aligned, next) = MicPcmStreamer.alignToFrameGroups(carry, chunk)
      assertEquals(0, aligned.size % MicPcmStreamer.FRAME_GROUP_BYTES)
      aligned.forEach { emitted.add(it) }
      carry = next
    }

    assertEquals(produced, emitted.size + carry.size)
    assertTrue(carry.size < MicPcmStreamer.FRAME_GROUP_BYTES)
    emitted.forEachIndexed { index, byte -> assertEquals(index.toByte(), byte) }
  }
}
