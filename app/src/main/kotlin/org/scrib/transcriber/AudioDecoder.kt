package org.scrib.transcriber

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class LowStorageException(val requiredMb: Long, val availableMb: Long) :
    RuntimeException("Low storage space: $availableMb MB available, approximately $requiredMb MB required for decoding.")

// 16 kHz mono float32 PCM in a direct or memory-mapped buffer, ready to hand to native code.
// Uses disk-backed memory mapping for long recordings to keep active RAM below 5 MB.
class DecodedAudio(
    val chunkBuffers: List<ByteBuffer>,
    val sampleCount: Int,
    val tempFile: File? = null,
    private val raf: RandomAccessFile? = null
) : AutoCloseable {
    constructor(samples: ByteBuffer, sampleCount: Int) : this(listOf(samples), sampleCount)
    constructor(samples: ByteBuffer, sampleCount: Int, tempFile: File?, raf: RandomAccessFile?) : this(listOf(samples), sampleCount, tempFile, raf)

    val samples: ByteBuffer
        get() {
            if (chunkBuffers.size == 1) {
                val dup = chunkBuffers[0].duplicate().order(ByteOrder.nativeOrder())
                dup.position(0)
                dup.limit(sampleCount * 4)
                return dup
            }
            val merged = ByteBuffer.allocateDirect(sampleCount * 4).order(ByteOrder.nativeOrder())
            val target = merged.asFloatBuffer()
            for (buf in chunkBuffers) {
                val dup = buf.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
                dup.position(0)
                target.put(dup)
            }
            merged.position(0)
            return merged
        }

    fun toFloatArray(): FloatArray {
        val array = FloatArray(sampleCount)
        var offset = 0
        for (buf in chunkBuffers) {
            val floatBuffer = buf.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
            floatBuffer.position(0)
            val len = minOf(floatBuffer.remaining(), sampleCount - offset)
            if (len > 0) {
                floatBuffer.get(array, offset, len)
                offset += len
            }
        }
        return array
    }

    /**
     * Reads a slice of samples into a provided destination buffer.
     * Prevents allocation churn during high-frequency frame analysis (e.g. 100 fps STE energy profiling).
     */
    fun readSlice(startSample: Int, length: Int, out: FloatArray): Int {
        if (length <= 0 || sampleCount <= 0 || out.isEmpty()) return 0
        val clampedStart = startSample.coerceIn(0, sampleCount)
        val clampedLen = minOf(length, minOf(out.size, sampleCount - clampedStart))
        if (clampedLen <= 0) return 0

        var remaining = clampedLen
        var outOffset = 0
        var currSample = clampedStart

        for (buf in chunkBuffers) {
            val bufFloatCount = buf.limit() / 4
            if (currSample >= bufFloatCount) {
                currSample -= bufFloatCount
                continue
            }
            val toRead = minOf(remaining, bufFloatCount - currSample)
            val floatBuf = buf.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
            floatBuf.position(currSample)
            floatBuf.get(out, outOffset, toRead)

            outOffset += toRead
            remaining -= toRead
            currSample = 0
            if (remaining <= 0) break
        }
        return clampedLen
    }

    /**
     * Reads a slice of samples without allocating the full recording.
     * Prevents contiguous heap allocation failures on long audio files.
     */
    fun getSlice(startSample: Int, length: Int): FloatArray {
        if (length <= 0 || sampleCount <= 0) return FloatArray(0)
        val clampedStart = startSample.coerceIn(0, sampleCount)
        val clampedLen = minOf(length, sampleCount - clampedStart)
        if (clampedLen <= 0) return FloatArray(0)

        val out = FloatArray(clampedLen)
        readSlice(clampedStart, clampedLen, out)
        return out
    }

    override fun close() {
        try { raf?.close() } catch (_: Throwable) {}
        for (buf in chunkBuffers) {
            unmapBuffer(buf)
        }
        try {
            if (tempFile?.delete() != true) {
                tempFile?.deleteOnExit()
            }
        } catch (_: Throwable) {}
    }

    private fun unmapBuffer(buffer: ByteBuffer) {
        try {
            val cleanerMethod = buffer.javaClass.getMethod("cleaner")
            cleanerMethod.isAccessible = true
            val cleaner = cleanerMethod.invoke(buffer)
            cleaner?.javaClass?.getMethod("clean")?.invoke(cleaner)
        } catch (_: Throwable) {
            try {
                val unsafeClass = Class.forName("sun.misc.Unsafe")
                val field = unsafeClass.getDeclaredField("theUnsafe")
                field.isAccessible = true
                val unsafe = field.get(null)
                val invokeCleaner = unsafeClass.getMethod("invokeCleaner", ByteBuffer::class.java)
                invokeCleaner.invoke(unsafe, buffer)
            } catch (_: Throwable) {}
        }
    }
}

// Where converted 16 kHz mono samples go: a decoded file, or a live stream's pending window.
interface PcmSink {
    fun add(sample: Float)
}

object AudioDecoder {

    private const val TAG = "AudioDecoder"
    private const val TARGET_RATE = 16000

    // Unbounded duration support: bounded only by available storage and 32-bit sample addressability.
    private const val MAX_TOTAL_SAMPLES = Int.MAX_VALUE - 1024

    // Five minutes, used when the container does not report a duration.
    private const val DEFAULT_CAPACITY_SAMPLES = TARGET_RATE * 300

    private const val STALL_TIMEOUT_NS = 60_000_000_000L

    private const val DRAIN_TIMEOUT_SECONDS = 5L

    fun cleanStaleTempFiles(cacheDir: File) {
        try {
            val files = cacheDir.listFiles { _, name ->
                (name.startsWith("scrib_decoded_") && name.endsWith(".pcm")) ||
                (name.startsWith("temp_export_") && name.endsWith(".docx"))
            }
            val oneHourAgo = System.currentTimeMillis() - 3600_000L
            files?.forEach { file ->
                if (file.lastModified() < oneHourAgo) {
                    file.delete()
                }
            }
        } catch (_: Throwable) {}
    }

    // Decodes whatever the platform supports (Opus/OGG voice notes, AAC/MP4 round videos) from the
    // file descriptor into 16 kHz mono float32 PCM. Uses async MediaCodec so decoding runs at the
    // codec's full speed instead of polling with per-packet timeouts. Each output chunk is
    // downmixed and resampled as it arrives — the raw stream is never buffered anywhere, so the
    // memory needed does not depend on the source rate or channel count.
    fun decodeToPcm16kMono(
        pfd: ParcelFileDescriptor,
        cancellation: CancellationToken? = null,
        cacheDir: File? = null
    ): DecodedAudio {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var handler: Handler? = null
        val handlerThread = HandlerThread("fgt-decode")
        val stopping = AtomicBoolean(false)
        var diskSink: DiskSampleSink? = null
        var decodeSuccess = false
        try {
            extractor.setDataSource(pfd.fileDescriptor)

            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    extractor.selectTrack(i)
                    inputFormat = format
                    break
                }
            }
            val format = inputFormat ?: throw RuntimeException("No audio track found")
            val mime = format.getString(MediaFormat.KEY_MIME)!!

            if (cacheDir != null) {
                cleanStaleTempFiles(cacheDir)
                val freeBytes = cacheDir.usableSpace
                val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else -1L
                val estimatedBytes = if (durationUs > 0) {
                    (durationUs / 1_000_000.0 * TARGET_RATE * 4 * 1.1).toLong()
                } else {
                    100_000_000L
                }
                val requiredBytes = maxOf(100_000_000L, estimatedBytes)
                if (freeBytes < requiredBytes) {
                    throw LowStorageException(requiredBytes / 1_000_000L, freeBytes / 1_000_000L)
                }
            }

            val sink: PcmSink = if (cacheDir != null) {
                val tempFile = File.createTempFile("scrib_decoded_", ".pcm", cacheDir)
                DiskSampleSink(tempFile).also { diskSink = it }
            } else {
                ChunkedSampleSink(initialCapacity(format))
            }
            val lock = Any()
            var converter: ChunkConverter? = null
            var finished = false
            val done = CountDownLatch(1)
            val lastActivity = AtomicLong(System.nanoTime())
            val rate = intArrayOf(
                if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else TARGET_RATE
            )
            val channels = intArrayOf(
                if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
            )
            val encoding = intArrayOf(
                if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
            )

            handlerThread.start()
            handler = Handler(handlerThread.looper)
            codec = MediaCodec.createDecoderByType(mime)
            codec.setCallback(object : MediaCodec.Callback() {
                private var inputDone = false
                private var scratch = ByteArray(0)

                override fun onInputBufferAvailable(mc: MediaCodec, index: Int) {
                    lastActivity.set(System.nanoTime())
                    if (inputDone || stopping.get() || cancellation?.isCancelled == true) {
                        return
                    }
                    try {
                        val buffer = mc.getInputBuffer(index) ?: return
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            mc.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            mc.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    } catch (e: Exception) {
                        // Codec state can change under us; throwing here would kill the process.
                        Log.w(TAG, "Feeding the decoder failed", e)
                        done.countDown()
                    }
                }

                override fun onOutputBufferAvailable(mc: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    lastActivity.set(System.nanoTime())
                    if (stopping.get()) {
                        return
                    }
                    if (cancellation?.isCancelled == true) {
                        mc.releaseOutputBuffer(index, false)
                        done.countDown()
                        return
                    }
                    try {
                        if (info.size > 0) {
                            val buffer = mc.getOutputBuffer(index)
                            if (buffer != null) {
                                if (scratch.size < info.size) {
                                    scratch = ByteArray(info.size)
                                }
                                buffer.position(info.offset)
                                buffer.get(scratch, 0, info.size)
                                synchronized(lock) {
                                    if (!finished) {
                                        val active = converter ?: ChunkConverter(
                                            rate[0], channels[0].coerceAtLeast(1), encoding[0], sink
                                        ).also { converter = it }
                                        active.feed(scratch, info.size)
                                    }
                                }
                            }
                        }
                        mc.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            done.countDown()
                        }
                    } catch (t: Throwable) {
                        // Running out of memory on a callback thread must not crash the process;
                        // keep whatever is already decoded.
                        Log.w(TAG, "PCM conversion failed", t)
                        done.countDown()
                    }
                }

                override fun onOutputFormatChanged(mc: MediaCodec, newFormat: MediaFormat) {
                    if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        rate[0] = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels[0] = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    if (newFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        encoding[0] = newFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    }
                }

                override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {
                    Log.w(TAG, "Codec error", e)
                    done.countDown()
                }
            }, handler)
            codec.configure(format, null, null, 0)
            codec.start()

            cancellation?.onCancel { done.countDown() }
            // No overall deadline: a long recording legitimately decodes for a long time. Give up
            // only when the codec stops making progress altogether.
            while (!done.await(1, TimeUnit.SECONDS)) {
                if (cancellation?.isCancelled == true) {
                    break
                }
                if (System.nanoTime() - lastActivity.get() > STALL_TIMEOUT_NS) {
                    Log.w(TAG, "Decoder stalled, keeping the decoded part")
                    break
                }
            }

            synchronized(lock) {
                finished = true
                converter?.flush()
                val result = when (sink) {
                    is DiskSampleSink -> sink.result()
                    is ChunkedSampleSink -> sink.result()
                    else -> throw IllegalStateException("Unsupported PCM sink type")
                }
                decodeSuccess = true
                return result
            }
        } finally {
            if (!decodeSuccess) {
                diskSink?.close()
                try { diskSink?.tempFile?.delete() } catch (_: Throwable) {}
            }
            // Callbacks use the codec and the extractor from the decode thread. Raise the flag,
            // then drain that thread, so none of them is in flight while we tear both down.
            stopping.set(true)
            awaitDrained(handler)
            try {
                codec?.stop()
            } catch (ignore: Exception) {
            }
            try {
                codec?.release()
            } catch (ignore: Exception) {
            }
            extractor.release()
            handlerThread.quitSafely()
        }
    }

    private fun awaitDrained(handler: Handler?) {
        val drained = CountDownLatch(1)
        if (handler?.post { drained.countDown() } != true) {
            return
        }
        try {
            drained.await(DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (ignore: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun initialCapacity(format: MediaFormat): Int {
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else -1L
        if (durationUs <= 0) {
            return DEFAULT_CAPACITY_SAMPLES
        }
        val estimated = durationUs / 1_000_000.0 * TARGET_RATE
        return (estimated * 1.02 + 2 * TARGET_RATE).toLong()
            .coerceAtMost(MAX_TOTAL_SAMPLES.toLong())
            .toInt()
    }

    private fun allocateSamples(samples: Int): ByteBuffer =
        ByteBuffer.allocateDirect(samples * 4).order(ByteOrder.nativeOrder())

    // Streams converted PCM samples directly to a disk-backed temporary file and memory-maps it.
    // Keeps active RAM usage under 5 MB regardless of whether the audio is 10 minutes or 10 hours!
    private class DiskSampleSink(val tempFile: File) : PcmSink, AutoCloseable {
        private val outStream = BufferedOutputStream(FileOutputStream(tempFile), 65536)
        private val byteBuffer = ByteArray(8192)
        private var bytePos = 0
        private var totalSamples = 0
        private var closed = false

        override fun add(sample: Float) {
            val bits = java.lang.Float.floatToRawIntBits(sample)
            byteBuffer[bytePos++] = (bits and 0xff).toByte()
            byteBuffer[bytePos++] = ((bits ushr 8) and 0xff).toByte()
            byteBuffer[bytePos++] = ((bits ushr 16) and 0xff).toByte()
            byteBuffer[bytePos++] = ((bits ushr 24) and 0xff).toByte()
            if (bytePos >= 8192) {
                outStream.write(byteBuffer, 0, bytePos)
                bytePos = 0
            }
            totalSamples++
        }

        fun result(): DecodedAudio {
            if (bytePos > 0) {
                outStream.write(byteBuffer, 0, bytePos)
                bytePos = 0
            }
            outStream.flush()
            outStream.close()
            closed = true
            val raf = RandomAccessFile(tempFile, "r")
            val channel = raf.channel
            val mmap = channel.map(FileChannel.MapMode.READ_ONLY, 0, tempFile.length())
            mmap.order(ByteOrder.nativeOrder())
            return DecodedAudio(listOf(mmap), totalSamples, tempFile, raf)
        }

        override fun close() {
            if (!closed) {
                try { outStream.close() } catch (_: Throwable) {}
                closed = true
            }
        }
    }

    // Accumulates converted PCM samples in fixed 5-minute direct memory chunks (19.2 MB each).
    // Appending new chunks requires ZERO growth copies and ZERO 150MB+ contiguous allocation failures!
    private class ChunkedSampleSink(initialCapacity: Int) : PcmSink {
        private val CHUNK_SIZE = TARGET_RATE * 300 // 5 minutes = 4,800,000 floats (19.2 MB)
        private val buffers = mutableListOf<ByteBuffer>()
        private var currentBuffer: ByteBuffer = allocateSamples(CHUNK_SIZE)
        private var currentFloats = currentBuffer.asFloatBuffer()
        private var totalSamples = 0

        init {
            buffers.add(currentBuffer)
        }

        override fun add(sample: Float) {
            if (totalSamples >= MAX_TOTAL_SAMPLES) return
            if (!currentFloats.hasRemaining()) {
                val nextBuffer = try {
                    allocateSamples(CHUNK_SIZE)
                } catch (e: OutOfMemoryError) {
                    Log.w(TAG, "Memory boundary reached at $totalSamples samples")
                    return
                }
                buffers.add(nextBuffer)
                currentBuffer = nextBuffer
                currentFloats = currentBuffer.asFloatBuffer()
            }
            currentFloats.put(sample)
            totalSamples++
        }

        fun result(): DecodedAudio {
            val preparedBuffers = mutableListOf<ByteBuffer>()
            var remainingFloats = totalSamples
            for (buf in buffers) {
                val floatsInThisBuffer = minOf(remainingFloats, CHUNK_SIZE)
                if (floatsInThisBuffer <= 0) break
                val dup = buf.duplicate().order(ByteOrder.nativeOrder())
                dup.position(0)
                dup.limit(floatsInThisBuffer * 4)
                preparedBuffers.add(dup)
                remainingFloats -= floatsInThisBuffer
            }
            return DecodedAudio(preparedBuffers, totalSamples)
        }
    }

    // Converts decoder output chunks (interleaved PCM at the source rate) into 16 kHz mono
    // samples as they arrive. Chunks need not be frame-aligned: partial frames are carried over.
    class ChunkConverter(
        private val srcRate: Int,
        private val channels: Int,
        private val encoding: Int,
        private val sink: PcmSink,
    ) {
        private val bytesPerSample = when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
            else -> throw RuntimeException("Unsupported PCM encoding $encoding")
        }
        private val bytesPerFrame = bytesPerSample * channels
        private val carry = ByteArray(bytesPerFrame)
        private var carried = 0

        private val step = srcRate.toDouble() / TARGET_RATE
        private var hist = 0f
        private var frames = 0L
        private var emitted = 0L

        fun feed(bytes: ByteArray, length: Int) {
            var pos = 0
            if (carried > 0) {
                val take = minOf(bytesPerFrame - carried, length)
                System.arraycopy(bytes, pos, carry, carried, take)
                carried += take
                pos += take
                if (carried < bytesPerFrame) {
                    return
                }
                pushFrame(carry, 0)
                carried = 0
            }
            while (pos + bytesPerFrame <= length) {
                pushFrame(bytes, pos)
                pos += bytesPerFrame
            }
            if (pos < length) {
                System.arraycopy(bytes, pos, carry, 0, length - pos)
                carried = length - pos
            }
        }

        // Emits the tail samples whose interpolation window never completed. Safe to call twice.
        fun flush() {
            val total = frames * TARGET_RATE / srcRate
            while (emitted < total) {
                sink.add(hist)
                emitted++
            }
        }

        // Downmixes one frame and emits every 16 kHz sample whose linear-interpolation window is
        // now complete. Only the previous mono sample is needed as history: an output between
        // source samples idx and idx+1 is emitted the moment idx+1 arrives.
        private fun pushFrame(bytes: ByteArray, offset: Int) {
            var acc = 0f
            for (c in 0 until channels) {
                acc += sampleAt(bytes, offset + c * bytesPerSample)
            }
            val cur = acc / channels
            while (true) {
                val srcPos = emitted * step
                val idx = srcPos.toLong()
                if (idx + 1 > frames) {
                    break
                }
                val frac = (srcPos - idx).toFloat()
                sink.add(hist + (cur - hist) * frac)
                emitted++
            }
            hist = cur
            frames++
        }

        private fun sampleAt(bytes: ByteArray, offset: Int): Float {
            return when (encoding) {
                AudioFormat.ENCODING_PCM_8BIT -> {
                    val u = bytes[offset].toInt() and 0xff
                    (u - 128) / 128f
                }
                AudioFormat.ENCODING_PCM_16BIT -> {
                    val lo = bytes[offset].toInt() and 0xff
                    val hi = bytes[offset + 1].toInt() and 0xff
                    ((hi shl 8) or lo).toShort() / 32768f
                }
                AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                    val b0 = bytes[offset].toInt() and 0xff
                    val b1 = bytes[offset + 1].toInt() and 0xff
                    val b2 = bytes[offset + 2].toInt()
                    val raw = (b2 shl 24) or (b1 shl 16) or (b0 shl 8)
                    (raw shr 8) / 8388608f
                }
                AudioFormat.ENCODING_PCM_32BIT -> {
                    val b0 = bytes[offset].toInt() and 0xff
                    val b1 = bytes[offset + 1].toInt() and 0xff
                    val b2 = bytes[offset + 2].toInt() and 0xff
                    val b3 = bytes[offset + 3].toInt() and 0xff
                    val raw = b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
                    raw / 2147483648f
                }
                AudioFormat.ENCODING_PCM_FLOAT -> {
                    val b0 = bytes[offset].toInt() and 0xff
                    val b1 = bytes[offset + 1].toInt() and 0xff
                    val b2 = bytes[offset + 2].toInt() and 0xff
                    val b3 = bytes[offset + 3].toInt() and 0xff
                    val bits = b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
                    java.lang.Float.intBitsToFloat(bits)
                }
                else -> 0f
            }
        }
    }
}
