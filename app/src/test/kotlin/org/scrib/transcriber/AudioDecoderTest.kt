package org.scrib.transcriber

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class AudioDecoderTest {

    @Test
    fun testInMemoryDecodedAudioSliceAndFloatArray() {
        val count = 32000 // 2 seconds of 16kHz audio
        val buf = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder())
        val floatBuf = buf.asFloatBuffer()
        for (i in 0 until count) {
            floatBuf.put((i.toFloat() / count))
        }

        val decoded = DecodedAudio(buf, count)
        assertEquals(count, decoded.sampleCount)

        val floatArray = decoded.toFloatArray()
        assertEquals(count, floatArray.size)
        assertEquals(0.0f, floatArray[0], 1e-6f)
        assertEquals(16000f / count, floatArray[16000], 1e-6f)

        val slice = decoded.getSlice(8000, 4000)
        assertEquals(4000, slice.size)
        assertEquals(8000f / count, slice[0], 1e-6f)
        assertEquals(11999f / count, slice[3999], 1e-6f)
    }

    @Test
    fun testDiskBackedDecodedAudioMemoryMappingAndCleanup() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "scrib_test_${System.currentTimeMillis()}").apply { mkdirs() }
        val tempPcm = File(tempDir, "scrib_decoded_test.pcm")

        val count = 48000 // 3 seconds at 16kHz
        val outStream = java.io.BufferedOutputStream(java.io.FileOutputStream(tempPcm), 65536)
        val byteBuf = ByteBuffer.allocate(count * 4).order(ByteOrder.nativeOrder())
        val floatBuf = byteBuf.asFloatBuffer()
        for (i in 0 until count) {
            floatBuf.put((i * 0.001f) - 1.0f)
        }
        outStream.write(byteBuf.array())
        outStream.flush()
        outStream.close()

        val rafRead = RandomAccessFile(tempPcm, "r")
        val inChannel = rafRead.channel
        val mmapRead = inChannel.map(FileChannel.MapMode.READ_ONLY, 0, tempPcm.length()).order(ByteOrder.nativeOrder())

        val decoded = DecodedAudio(mmapRead, count, tempPcm, rafRead)
        assertEquals(count, decoded.sampleCount)

        // Test slice extraction
        val slice = decoded.getSlice(16000, 16000)
        assertEquals(16000, slice.size)
        assertEquals((16000 * 0.001f) - 1.0f, slice[0], 1e-5f)

        // Test readSlice destination buffer
        val dest = FloatArray(8000)
        val readCount = decoded.readSlice(24000, 8000, dest)
        assertEquals(8000, readCount)
        assertEquals((24000 * 0.001f) - 1.0f, dest[0], 1e-5f)

        // Test close cleans up temp file
        assertTrue(tempPcm.exists())
        decoded.close()

        tempDir.deleteRecursively()
    }

    @Test
    fun testCleanStaleTempFiles() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "scrib_stale_${System.currentTimeMillis()}").apply { mkdirs() }
        val oldFile = File(tempDir, "scrib_decoded_old.pcm").apply {
            createNewFile()
            setLastModified(System.currentTimeMillis() - 7200_000L) // 2 hours ago
        }
        val newFile = File(tempDir, "scrib_decoded_new.pcm").apply {
            createNewFile()
            setLastModified(System.currentTimeMillis()) // fresh
        }

        AudioDecoder.cleanStaleTempFiles(tempDir)

        assertFalse("Old file should be deleted", oldFile.exists())
        assertTrue("New file should be preserved", newFile.exists())

        tempDir.deleteRecursively()
    }
}
