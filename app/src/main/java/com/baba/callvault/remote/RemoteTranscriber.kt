/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.remote

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.baba.callvault.server.speakers.OfflineSpeakerLabeller
import com.baba.callvault.transcription.AudioDecoder
import com.baba.callvault.transcription.ChunkPlan
import com.baba.callvault.transcription.TranscriptSegment
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Transcription done by a server on the local network instead of by whisper on the phone.
 *
 * The phone still decodes the recording (issue #38's speaker labelling rides on that decode, and
 * nothing on the network carries channel energy), then uploads the **original file** rather than
 * decoded PCM: a 30-minute call is a few megabytes of Opus and a dozen times the same audio in
 * 16 kHz WAV, and any ASR server worth its salt decodes the container itself.
 *
 * The server answers with segments when it can ([parse] accepts both the millisecond shape and
 * the OpenAI `verbose_json` seconds shape) and with plain text when it cannot; plain text becomes
 * one segment spanning the recording, which costs the tap-to-seek resolution inside that stretch
 * but keeps every word. A transcript missing timestamps is degraded; one missing words is broken.
 *
 * Like [com.baba.callvault.transcription.TranscriptionEngine], one run at a time: two in flight
 * would interleave their progress, their aborts, and the server's own queue for no gain.
 */
object RemoteTranscriber {

    private const val TAG = "CV:ServerASR"

    /** Upload guard. A recording this big is not a call, and streaming it over the tailnet on mobile data would hurt. */
    private const val MAX_UPLOAD_BYTES = 512L * 1024 * 1024

    @Volatile
    var isRunning: Boolean = false
        private set

    private val mutex = Mutex()
    private val aborted = AtomicBoolean(false)
    private val connection = AtomicReference<HttpURLConnection>()

    /** 0-100 while [isRunning]; coarse by nature, the caller's time-based tracker smooths it. */
    @Volatile
    var progressPercent: Int = 0
        private set

    /**
     * Asks a run in progress to stop. Rides on [com.baba.callvault.transcription.TranscriptionEngine]
     * 's `requestAbort`, which calls this too, so every existing Stop route covers a server run
     * without knowing the difference.
     */
    fun requestAbort() {
        aborted.set(true)
        connection.get()?.let { conn ->
            runCatching { conn.inputStream?.close() }
            runCatching { conn.disconnect() }
        }
    }

    fun wasAborted(): Boolean = aborted.get()

    /**
     * Transcribes [uri] on the server at [baseUrl] (optionally with a bearer [apiKey]).
     *
     * @param language the ISO pin from settings, sent as a field the server may honour; servers
     *   that ignore it behave as upstream expects of auto-detect.
     * @return segments in recording time, or empty when the server heard no speech.
     * @throws IOException when the request fails or was stopped — the caller distinguishes the
     *   two through [wasAborted], exactly as it does for a stopped local run.
     */
    suspend fun transcribe(
        context: Context,
        uri: Uri,
        baseUrl: String,
        apiKey: String,
        language: String?,
        /** The same channel-energy detector the local path feeds during decode; null for none. */
        speakers: OfflineSpeakerLabeller?,
    ): List<TranscriptSegment> = withContext(Dispatchers.IO) {
        mutex.withLock {
            aborted.set(false)
            progressPercent = 0
            isRunning = true
            try {
                // Decode first, upload second: the labeller has to see the interleaved channels of
                // THIS phone's recording whether or not whisper runs here, and the decode is the
                // same one the local path does for exactly that reason. The float PCM itself is
                // thrown away — only the upload matters afterwards.
                if (speakers != null) feedSpeakerLabeller(context, uri, speakers)
                if (aborted.get()) throw IOException("Stopped before upload")

                val fileBytes = readFile(context, uri)
                val durationMs = AudioDecoder.durationMs(context, uri)

                progressPercent = 25
                AppLogger.i(TAG, "Uploading ${fileBytes.size / 1024} kB (${durationMs / 1000}s) to $baseUrl")
                val response = ServerHttp.postMultipartFile(
                    url = "$baseUrl/v1/audio/transcriptions?output=json_segments",
                    fields = listOf("model" to "callvault") +
                        (language?.let { listOf("language" to it) } ?: emptyList()),
                    fileBytes = fileBytes,
                    fileName = displayNameOf(context, uri),
                    fileContentType = contentTypeOf(displayNameOf(context, uri)),
                    apiKey = apiKey,
                    // A server on CPU can take a real-time hour for an hour of speech. The read
                    // timeout has to outlast the longest plausible call, not a comfortable one.
                    readTimeoutMs = 4 * 60 * 60 * 1000,
                    connection = connection,
                )

                if (aborted.get()) throw IOException("Stopped during upload")
                progressPercent = 95

                val segments = parse(response.body, durationMs)
                AppLogger.i(TAG, "Server returned ${segments.size} segment(s)")
                segments
            } finally {
                isRunning = false
                progressPercent = 0
            }
        }
    }

    /**
     * Decodes the whole recording feeding only the speaker detector, in [ChunkPlan] passes so the
     * heap holds one slice at a time — the identical reason the local engine chunks.
     */
    private fun feedSpeakerLabeller(context: Context, uri: Uri, labeller: OfflineSpeakerLabeller) {
        val durationMs = runCatching { AudioDecoder.durationMs(context, uri) }.getOrDefault(0L)
        val plan = ChunkPlan.plan(durationMs)
        val singlePass = plan.size == 1
        plan.forEachIndexed { index, chunk ->
            if (aborted.get()) return
            runCatching {
                AudioDecoder.decodeRange(
                    context, uri,
                    fromMs = if (singlePass) 0L else chunk.decodeFromMs,
                    toMs = if (singlePass || chunk.endMs <= 0L) Long.MAX_VALUE else chunk.endMs,
                    onInterleaved = { pcm, length, channels, rate, startMs ->
                        runCatching { labeller.accept(pcm, length, channels, rate, startMs) }
                            .onFailure { AppLogger.w(TAG, "speaker labelling skipped this chunk: ${it.message}") }
                        Unit
                    },
                ) { aborted.get() }
            }.onFailure { AppLogger.w(TAG, "speaker decode pass ${index + 1} failed: ${it.message}") }
        }
    }

    private fun readFile(context: Context, uri: Uri): ByteArray =
        context.contentResolver.openInputStream(uri)?.use { input ->
            val bytes = input.readBytes()
            if (bytes.size.toLong() > MAX_UPLOAD_BYTES) {
                throw IOException("Recording is ${bytes.size / 1024 / 1024} MB; too big to upload")
            }
            bytes
        } ?: throw IOException("Could not open $uri for upload")

    private fun displayNameOf(context: Context, uri: Uri): String {
        val fromColumns = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }
        return fromColumns ?: uri.lastPathSegment?.substringAfterLast('/') ?: "recording.ogg"
    }

    private fun contentTypeOf(name: String): String = when (name.substringAfterLast('.', "")) {
        "ogg", "oga" -> "audio/ogg"
        "m4a", "mp4", "aac" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        else -> "application/octet-stream"
    }

    /**
     * Server answer → segments, accepting every timestamp shape an honest LAN server produces:
     *
     *  - `{"segments":[{"start_ms":…,"end_ms":…,"text":…}]}` — CallVault's own extension, exact
     *    milliseconds, no float drift;
     *  - `{"segments":[{"start":<seconds>,"end":<seconds>,"text":…}]}` — OpenAI `verbose_json`;
     *  - `{"text":"…"}` with no usable segments — one segment across the whole recording.
     *
     * Never throws on shape: a server whose JSON surprises us still gets its words kept.
     */
    internal fun parse(body: String, durationMs: Long): List<TranscriptSegment> {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()

        val segments = mutableListOf<TranscriptSegment>()
        runCatching {
            val array = json.optJSONArray("segments") ?: return@runCatching
            for (i in 0 until array.length()) {
                val s = array.optJSONObject(i) ?: continue
                val text = s.optString("text").trim()
                if (text.isEmpty()) continue
                val startMs = when {
                    s.has("start_ms") -> s.optLong("start_ms")
                    else -> (s.optDouble("start") * 1000).toLong()
                }
                val endMs = when {
                    s.has("end_ms") -> s.optLong("end_ms")
                    else -> (s.optDouble("end") * 1000).toLong()
                }
                segments += TranscriptSegment(startMs = startMs.coerceAtLeast(0L), endMs = endMs, text = text)
            }
        }.onFailure { AppLogger.w(TAG, "Could not read segments: ${it.message}") }

        if (segments.isNotEmpty()) return segments

        val text = json.optString("text").trim()
        return if (text.isEmpty()) emptyList()
        else listOf(TranscriptSegment(startMs = 0L, endMs = durationMs, text = text))
    }
}
