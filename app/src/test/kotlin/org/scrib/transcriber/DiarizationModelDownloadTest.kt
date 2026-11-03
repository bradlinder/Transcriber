package org.scrib.transcriber

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class DiarizationModelDownloadTest {

    @Test
    fun downloadWeSpeakerModelIntoAssets() {
        val targetDir = File("src/main/assets/models/diarization").apply { mkdirs() }
        val camFile = File(targetDir, "wespeaker_en_voxceleb_CAM++.onnx")

        if (camFile.exists() && camFile.length() > 10_000_000L) {
            println("WeSpeaker CAM++ model already present in assets/models/diarization/ (${camFile.length()} bytes)")
            return
        }

        val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/wespeaker_en_voxceleb_CAM++.onnx"
        println("Downloading WeSpeaker CAM++ model from $url...")

        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 60000
        conn.readTimeout = 180000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")

        val code = conn.responseCode
        if (code in 301..308) {
            val redirectUrl = conn.getHeaderField("Location")
            val conn2 = URL(redirectUrl).openConnection() as HttpURLConnection
            conn2.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn2.inputStream.use { input ->
                FileOutputStream(camFile).use { output -> input.copyTo(output) }
            }
        } else {
            conn.inputStream.use { input ->
                FileOutputStream(camFile).use { output -> input.copyTo(output) }
            }
        }

        assertTrue("Downloaded model file must exist", camFile.exists())
        assertTrue("Downloaded model file size must be > 10 MB", camFile.length() > 10_000_000L)
        println("Successfully downloaded wespeaker_en_voxceleb_CAM++.onnx (${camFile.length()} bytes) to assets/models/diarization/")
    }

    @Test
    fun downloadSileroVadModelIntoAssets() {
        val targetDir = File("src/main/assets/models/diarization").apply { mkdirs() }
        val vadFile = File(targetDir, "silero_vad.onnx")

        if (vadFile.exists() && vadFile.length() > 500_000L) {
            println("Silero VAD model already present in assets/models/diarization/ (${vadFile.length()} bytes)")
            return
        }

        val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"
        println("Downloading Silero VAD model from $url...")

        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 60000
        conn.readTimeout = 180000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")

        val code = conn.responseCode
        if (code in 301..308) {
            val redirectUrl = conn.getHeaderField("Location")
            val conn2 = URL(redirectUrl).openConnection() as HttpURLConnection
            conn2.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn2.inputStream.use { input ->
                FileOutputStream(vadFile).use { output -> input.copyTo(output) }
            }
        } else {
            conn.inputStream.use { input ->
                FileOutputStream(vadFile).use { output -> input.copyTo(output) }
            }
        }

        assertTrue("Downloaded VAD model file must exist", vadFile.exists())
        assertTrue("Downloaded VAD model file size must be > 500 KB", vadFile.length() > 500_000L)
        println("Successfully downloaded silero_vad.onnx (${vadFile.length()} bytes) to assets/models/diarization/")
    }
}
