package space.valasiadis.videodelay

import android.os.SharedMemory
import java.nio.ByteBuffer

/**
 * Circular store of encoded video packets, addressed by a monotonically increasing sequence number.
 *
 * Packet bytes live in an ashmem region (SharedMemory), not on the Java heap, so a 30 s 4K buffer
 * (a few hundred MB) doesn't run into the per-app heap limit. When space runs out, or a packet is
 * older than [maxAgeUs], the oldest packets are evicted.
 *
 * Thread-safe: one writer (encoder callback), several readers (playback, recorder).
 */
class PacketRing(capacityBytes: Int, private val maxAgeUs: Long) : AutoCloseable {

    class Meta {
        var size = 0
        var ptsUs = 0L
        var key = false
    }

    private val memory = SharedMemory.create("videodelay-ring", capacityBytes)
    private val data: ByteBuffer = memory.mapReadWrite()
    private val cap = capacityBytes

    private val slots = 16384 // 60 fps * 33 s ≈ 2000 packets; plenty of headroom
    private val offs = IntArray(slots)
    private val sizes = IntArray(slots)
    private val pts = LongArray(slots)
    private val keys = BooleanArray(slots)

    private var headSeq = 0L // oldest stored
    private var tailSeq = 0L // next to be written
    private var writePos = 0

    private val lock = Any()

    val oldestSeq: Long get() = synchronized(lock) { headSeq }
    val nextSeq: Long get() = synchronized(lock) { tailSeq }

    private fun slot(seq: Long) = (seq % slots).toInt()

    fun add(src: ByteBuffer, ptsUs: Long, key: Boolean) {
        val size = src.remaining()
        if (size == 0 || size > cap / 4) return
        synchronized(lock) {
            var pos = writePos
            if (pos + size > cap) pos = 0
            // Evict packets whose bytes the new packet would overwrite. Packets are laid out in
            // sequence order, so only the head can be the first overlap.
            while (tailSeq > headSeq) {
                val s = slot(headSeq)
                val ho = offs[s]
                val overlaps = ho < pos + size && ho + sizes[s] > pos
                if (!overlaps && tailSeq - headSeq < slots) break
                headSeq++
            }
            // Age-based eviction keeps memory bounded to what the max delay can ever need.
            while (tailSeq > headSeq && pts[slot(headSeq)] < ptsUs - maxAgeUs) headSeq++

            val dup = data.duplicate()
            dup.position(pos)
            dup.put(src)
            val s = slot(tailSeq)
            offs[s] = pos
            sizes[s] = size
            pts[s] = ptsUs
            keys[s] = key
            tailSeq++
            writePos = pos + size
        }
    }

    /** Returns the pts of [seq], or null if it was evicted or not written yet. */
    fun ptsOf(seq: Long): Long? = synchronized(lock) {
        if (seq < headSeq || seq >= tailSeq) null else pts[slot(seq)]
    }

    fun sizeOf(seq: Long): Int = synchronized(lock) {
        if (seq < headSeq || seq >= tailSeq) -1 else sizes[slot(seq)]
    }

    /** Copies packet [seq] into [dst] at its current position. Returns false if unavailable or [dst] is too small. */
    fun read(seq: Long, dst: ByteBuffer, meta: Meta): Boolean = synchronized(lock) {
        if (seq < headSeq || seq >= tailSeq) return false
        val s = slot(seq)
        if (dst.remaining() < sizes[s]) return false
        val dup = data.duplicate()
        dup.limit(offs[s] + sizes[s])
        dup.position(offs[s])
        dst.put(dup)
        meta.size = sizes[s]
        meta.ptsUs = pts[s]
        meta.key = keys[s]
        true
    }

    /** Latest keyframe with pts <= [ptsUs], else the oldest keyframe stored, else -1. */
    fun keyframeAtOrBefore(ptsUs: Long): Long = synchronized(lock) {
        var seq = tailSeq - 1
        while (seq >= headSeq) {
            val s = slot(seq)
            if (keys[s] && pts[s] <= ptsUs) return seq
            seq--
        }
        firstKeyframeLocked(headSeq)
    }

    /** First keyframe with pts >= [ptsUs], or -1 if it hasn't arrived yet. */
    fun firstKeyframeAtOrAfter(ptsUs: Long): Long = synchronized(lock) {
        var seq = headSeq
        while (seq < tailSeq) {
            val s = slot(seq)
            if (keys[s] && pts[s] >= ptsUs) return seq
            seq++
        }
        -1
    }

    /** First keyframe with sequence number >= [fromSeq], or -1. */
    fun firstKeyframeFrom(fromSeq: Long): Long = synchronized(lock) { firstKeyframeLocked(maxOf(fromSeq, headSeq)) }

    private fun firstKeyframeLocked(from: Long): Long {
        var seq = from
        while (seq < tailSeq) {
            if (keys[slot(seq)]) return seq
            seq++
        }
        return -1
    }

    /** Seconds of video currently held. */
    fun spanUs(): Long = synchronized(lock) {
        if (tailSeq == headSeq) 0 else pts[slot(tailSeq - 1)] - pts[slot(headSeq)]
    }

    fun newestPtsUs(): Long? = synchronized(lock) { if (tailSeq == headSeq) null else pts[slot(tailSeq - 1)] }

    override fun close() {
        SharedMemory.unmap(data)
        memory.close()
    }
}
