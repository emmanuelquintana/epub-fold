package com.emmanuel.epubfold

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.coroutines.resume

/** Voces del motor de texto a voz, con nombres legibles. */
object Voices {

    /** Voces del idioma [lang], las de más calidad y sin conexión primero. */
    fun forLanguage(tts: TextToSpeech, lang: String): List<Voice> {
        val l = lang.take(2).lowercase().ifEmpty { Locale.getDefault().language }
        return runCatching { tts.voices?.toList() }.getOrNull().orEmpty()
            .filter { it.locale.language == l && !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            .sortedWith(compareByDescending<Voice> { it.quality }.thenBy { it.isNetworkConnectionRequired }.thenBy { it.name })
    }

    fun label(v: Voice, index: Int): String {
        val quality = when {
            v.quality >= Voice.QUALITY_VERY_HIGH -> "Muy alta calidad"
            v.quality >= Voice.QUALITY_HIGH -> "Alta calidad"
            v.quality >= Voice.QUALITY_NORMAL -> "Normal"
            else -> "Básica"
        }
        val net = if (v.isNetworkConnectionRequired) "necesita internet" else "sin conexión"
        val country = v.locale.displayCountry.ifEmpty { v.locale.displayLanguage }
        return "Voz ${index + 1} · $country · $quality · $net"
    }
}

/**
 * Guarda un capítulo como audio (M4A) en Música/EpubFold, para escucharlo con
 * cualquier reproductor. Usa la misma voz y velocidad que la lectura en voz alta.
 */
class AudioExport(private val context: Context) {

    @Volatile var cancelled = false

    /** Devuelve dónde se guardó. [progress] recibe 0..1. */
    suspend fun export(
        text: CharSequence,
        fileName: String,
        language: String,
        voiceName: String?,
        rate: Float,
        progress: (Float) -> Unit,
    ): String {
        val work = File(context.cacheDir, "tts-export").apply { deleteRecursively(); mkdirs() }
        try {
            val tts = initTts()
            try {
                if (language.isNotEmpty()) tts.language = Locale.forLanguageTag(language)
                voiceName?.let { n -> tts.voices?.firstOrNull { it.name == n }?.let { tts.voice = it } }
                tts.setSpeechRate(rate)
                val chunks = split(text, (TextToSpeech.getMaxSpeechInputLength() - 100).coerceIn(500, 3500))
                if (chunks.isEmpty()) throw IllegalArgumentException("El capítulo no tiene texto")
                val wavs = ArrayList<File>()
                chunks.forEachIndexed { i, chunk ->
                    if (cancelled) throw kotlinx.coroutines.CancellationException()
                    val f = File(work, "part$i.wav")
                    synth(tts, chunk, f)
                    if (f.length() > 44) wavs += f
                    progress((i + 1f) / chunks.size * 0.85f)
                }
                val m4a = File(work, "out.m4a")
                val ok = runCatching { encodeAac(wavs, m4a) }.isSuccess
                progress(0.95f)
                return if (ok) publish(m4a, "$fileName.m4a", "audio/mp4")
                else {
                    val wav = File(work, "out.wav")
                    concatWav(wavs, wav)
                    publish(wav, "$fileName.wav", "audio/x-wav")
                }
            } finally {
                tts.shutdown()
            }
        } finally {
            work.deleteRecursively()
        }
    }

    private suspend fun initTts(): TextToSpeech = suspendCancellableCoroutine { cont ->
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) cont.resume(engine!!)
            else cont.resumeWith(Result.failure(IllegalStateException("No hay motor de voz")))
        }
    }

    private suspend fun synth(tts: TextToSpeech, text: String, out: File) = suspendCancellableCoroutine<Unit> { cont ->
        val id = "x" + System.nanoTime()
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(Unit) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId == id && cont.isActive) cont.resumeWith(Result.failure(IllegalStateException("Error del motor de voz")))
            }
        })
        val r = tts.synthesizeToFile(text, Bundle(), out, id)
        if (r != TextToSpeech.SUCCESS && cont.isActive) cont.resumeWith(Result.failure(IllegalStateException("El motor de voz rechazó el texto")))
    }

    /** Trozos que caben en el motor, cortando en finales de frase. */
    private fun split(text: CharSequence, max: Int): List<String> {
        val clean = text.toString().replace('￼', ' ')
        val out = ArrayList<String>()
        var s = 0
        while (s < clean.length) {
            var e = (s + max).coerceAtMost(clean.length)
            if (e < clean.length) {
                val cut = clean.lastIndexOfAny(charArrayOf('.', '!', '?', '\n', '…'), e)
                if (cut > s + max / 3) e = cut + 1
            }
            val part = clean.substring(s, e).trim()
            if (part.any { it.isLetterOrDigit() }) out += part
            s = e
        }
        return out
    }

    private class Wav(val rate: Int, val channels: Int, val bits: Int, val dataOffset: Long, val dataSize: Long)

    private fun readWav(f: File): Wav {
        RandomAccessFile(f, "r").use { raf ->
            val buf = ByteArray(12)
            raf.readFully(buf)
            var rate = 22050; var channels = 1; var bits = 16
            while (raf.filePointer < raf.length()) {
                val hdr = ByteArray(8)
                raf.readFully(hdr)
                val id = String(hdr, 0, 4, Charsets.US_ASCII)
                val size = ByteBuffer.wrap(hdr, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                if (id == "fmt ") {
                    val fmt = ByteArray(size.toInt())
                    raf.readFully(fmt)
                    val bb = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                    channels = bb.getShort(2).toInt()
                    rate = bb.getInt(4)
                    bits = bb.getShort(14).toInt()
                } else if (id == "data") {
                    // Algunos motores dejan el tamaño a 0 al escribir en streaming
                    val real = if (size == 0L || size > raf.length()) raf.length() - raf.filePointer else size
                    return Wav(rate, channels, bits, raf.filePointer, real)
                } else {
                    raf.seek(raf.filePointer + size + (size and 1))
                }
            }
        }
        throw IllegalArgumentException("WAV sin datos")
    }

    private fun encodeAac(wavs: List<File>, out: File) {
        val first = readWav(wavs.first())
        if (first.bits != 16) throw IllegalStateException("formato no compatible")
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, first.rate, first.channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 48_000 * first.channels)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        val info = MediaCodec.BufferInfo()
        val bytesPerSec = first.rate * first.channels * 2L
        var presented = 0L
        var inputDone = false
        val pcm = PcmReader(wavs.map { readWav(it) to it })
        try {
            while (true) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val b = codec.getInputBuffer(i)!!
                        b.clear()
                        val n = pcm.read(b, b.capacity().coerceAtMost(16384))
                        val pts = presented * 1_000_000L / bytesPerSec
                        if (n <= 0) {
                            codec.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, pts, 0)
                            presented += n
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                    }
                    o >= 0 -> {
                        val ob = codec.getOutputBuffer(o)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && track >= 0) {
                            ob.position(info.offset)
                            ob.limit(info.offset + info.size)
                            muxer.writeSampleData(track, ob, info)
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
                if (cancelled) throw kotlinx.coroutines.CancellationException()
            }
        } finally {
            pcm.close()
            runCatching { codec.stop() }
            codec.release()
            runCatching { muxer.stop() }
            muxer.release()
        }
    }

    /** Lee seguidos los datos PCM de varios WAV. */
    private class PcmReader(private val parts: List<Pair<Wav, File>>) {
        private var index = 0
        private var raf: RandomAccessFile? = null
        private var left = 0L
        private val tmp = ByteArray(16384)

        fun read(dst: ByteBuffer, max: Int): Int {
            while (true) {
                if (raf == null) {
                    if (index >= parts.size) return -1
                    val (w, f) = parts[index++]
                    raf = RandomAccessFile(f, "r").also { it.seek(w.dataOffset) }
                    left = w.dataSize
                }
                if (left <= 0) {
                    raf?.close(); raf = null
                    continue
                }
                val n = raf!!.read(tmp, 0, minOf(max.toLong(), left, tmp.size.toLong()).toInt())
                if (n <= 0) {
                    raf?.close(); raf = null
                    continue
                }
                left -= n
                dst.put(tmp, 0, n)
                return n
            }
        }

        fun close() { raf?.close() }
    }

    private fun concatWav(wavs: List<File>, out: File) {
        val infos = wavs.map { readWav(it) to it }
        val first = infos.first().first
        val total = infos.sumOf { it.first.dataSize }
        out.outputStream().buffered().use { os ->
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()).putInt((36 + total).toInt()).put("WAVE".toByteArray())
            h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(first.channels.toShort())
                .putInt(first.rate).putInt(first.rate * first.channels * first.bits / 8)
                .putShort((first.channels * first.bits / 8).toShort()).putShort(first.bits.toShort())
            h.put("data".toByteArray()).putInt(total.toInt())
            os.write(h.array())
            infos.forEach { (w, f) ->
                RandomAccessFile(f, "r").use { raf ->
                    raf.seek(w.dataOffset)
                    val buf = ByteArray(65536)
                    var left = w.dataSize
                    while (left > 0) {
                        val n = raf.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
                        if (n <= 0) break
                        os.write(buf, 0, n)
                        left -= n
                    }
                }
            }
        }
    }

    /** Copia a Música/EpubFold (visible para cualquier reproductor). */
    private fun publish(src: File, name: String, mime: String): String {
        val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, safe)
                put(MediaStore.Audio.Media.MIME_TYPE, mime)
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/EpubFold")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri: Uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("No se pudo crear el archivo de audio")
            resolver.openOutputStream(uri)?.use { os -> src.inputStream().use { it.copyTo(os) } }
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return "Música/EpubFold/$safe"
        }
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "EpubFold").apply { mkdirs() }
        val dest = File(dir, safe)
        src.copyTo(dest, overwrite = true)
        return dest.absolutePath
    }
}
