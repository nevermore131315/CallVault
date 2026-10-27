/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.remote

import android.content.Context
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.summary.CallSummary
import com.baba.callvault.summary.SummaryModelHost
import com.baba.callvault.summary.SummarySession
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Summarisation done by an OpenAI-compatible server on the local network.
 *
 * **The model is whatever the server already has loaded** unless a name is typed in Settings.
 * That is the point of the feature: on a home server where a second model load costs gigabytes
 * and seconds, summarising with the model that is already resident costs the call nothing extra.
 * The name is resolved once per run from the server's own state — llama-swap's `/running` first,
 * an OpenAI `/v1/models` list second — so a server whose loaded model changes between calls
 * needs no reconfiguration.
 *
 * Three departures from the on-device path worth knowing:
 *  - The GBNF grammar is sent (llama.cpp servers honour it) but **servers may ignore it** —
 *    an unknown field in an OpenAI request is not an error. With no grammar, malformed JSON is
 *    a real possibility, so output is run through [extractJson] (first balanced object, thinking
 *    text discarded) and one retry with a stricter instruction when nothing balanced comes back.
 *  - `chat_template_kwargs.enable_thinking` is sent so reasoning models put the answer in
 *    `content` rather than only `reasoning_content`; a server that does not know the flag
 *    ignores it, and [pickText] reads `reasoning_content` as a fallback.
 *  - Generation is greedy (`temperature: 0`), matching what the local engine does. Summaries
 *    are a record of a real call; sampling would be inventing with extra steps.
 */
class RemoteSummaryHost(private val context: Context) : SummaryModelHost {

    companion object {
        private const val TAG = "CV:ServerSummary"

        /** The run in flight, so Stop routes that only know [com.baba.callvault.summary.SummaryEngine] can reach it. */
        private val active = AtomicReference<RemoteSummaryHost?>()

        @Volatile
        private var busy = false

        /** Whether a server summary is running, for the same gates [com.baba.callvault.summary.SummaryEngine.isRunning] feeds. */
        fun isBusy(): Boolean = busy

        fun requestAbortAnywhere() = active.get()?.requestAbort()

        fun wasAbortedAnywhere(): Boolean = active.get()?.wasAborted() ?: false

        /** `content`, or `reasoning_content` when a thinking model answered only there. */
        internal fun pickText(message: JSONObject): String {
            val content = message.optString("content").trim()
            if (content.isNotEmpty() && content != "null") return content
            val reasoning = message.optString("reasoning_content").trim()
            if (reasoning.isNotEmpty()) return reasoning
            throw IllegalStateException("The server answered with an empty message")
        }

        /**
         * The first balanced `{…}` in [text], string- and escape-aware, or null when there is none.
         *
         * Servers hand back markdown fences around the object, or a thinking model's preamble before
         * it. This takes the object and drops the rest; [CallSummary.parse] then either reads it or
         * the run reports a parse failure honestly, rather than the app inventing a summary of prose.
         */
        internal fun extractJson(text: String): String? {
            val start = text.indexOf('{')
            if (start < 0) return null
            var depth = 0
            var inString = false
            var escaped = false
            for (i in start until text.length) {
                val c = text[i]
                if (escaped) {
                    // The char a backslash escaped is data, never a delimiter — skip it whole.
                    escaped = false
                    continue
                }
                when (c) {
                    '\\' -> if (inString) escaped = true
                    '"' -> inString = !inString
                    '{' -> if (!inString) depth++
                    '}' -> if (!inString) {
                        depth--
                        if (depth == 0) return text.substring(start, i + 1)
                    }
                }
            }
            return null
        }
    }

    private val mutex = Mutex()
    private val aborted = AtomicBoolean(false)
    private val connection = AtomicReference<HttpURLConnection>()

    fun requestAbort() {
        aborted.set(true)
        connection.get()?.let { conn ->
            runCatching { conn.inputStream?.close() }
            runCatching { conn.disconnect() }
        }
    }

    fun wasAborted(): Boolean = aborted.get()

    /**
     * [modelPath] is ignored: the model lives on the server. Resolving which one is the server's
     * business, asked once per run so every chunk of a multi-chunk call summarises against the
     * same model even if the server swaps what it holds mid-run.
     */
    override suspend fun run(
        modelPath: String,
        block: suspend (SummarySession) -> CallSummary?
    ): CallSummary? = withContext(Dispatchers.IO) {
        mutex.withLock {
            aborted.set(false)
            active.set(this@RemoteSummaryHost)
            busy = true
            try {
                val prefs = AppPreferences(context)
                val baseUrl = prefs.getServerSummaryUrl()
                val apiKey = prefs.getServerSummaryKey()
                val model = resolveModel(baseUrl, apiKey, prefs.getServerSummaryModel())
                AppLogger.i(TAG, "Summarising on $baseUrl with $model")
                block { prompt, maxTokens, grammar ->
                    generate(baseUrl, apiKey, model, prompt, maxTokens, grammar)
                }
            } finally {
                busy = false
                active.compareAndSet(this@RemoteSummaryHost, null)
            }
        }
    }

    /**
     * Which model to send. A pinned name wins. Otherwise llama-swap's `/running` answers exactly
     * the question ("what is loaded?"); servers without that endpoint fall back to `/v1/models`,
     * preferring a status marked loaded and accepting a lone entry outright — a single-model
     * server needs no naming, while a multi-model server with no load state has nothing honest to
     * pick, and guessing there would be the one failure mode worse than asking the user to name it.
     */
    private fun resolveModel(baseUrl: String, apiKey: String, pinned: String): String {
        if (pinned.isNotBlank()) return pinned

        runCatching {
            val body = ServerHttp.get("$baseUrl/running", apiKey, connection = connection).body
            val running = JSONObject(body).optJSONArray("running") ?: JSONArray()
            (0 until running.length())
                .mapNotNull { running.optJSONObject(it) }
                .firstOrNull { it.optString("state") in setOf("ready", "loading") }
                ?.optString("model")
                ?.takeIf { it.isNotBlank() }
        }.onSuccess { it?.let { model -> return model } }
            .onFailure { AppLogger.i(TAG, "/running not usable here: ${it.message}") }

        val listed = runCatching {
            val data = JSONObject(ServerHttp.get("$baseUrl/v1/models", apiKey, connection = connection).body)
                .optJSONArray("data") ?: JSONArray()
            (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("id") }
                .filter { it.isNotBlank() }
        }.getOrDefault(emptyList())

        return when (listed.size) {
            1 -> listed.first()
            0 -> throw IllegalStateException("No model found on the server at $baseUrl")
            else -> throw IllegalStateException(
                "The server at $baseUrl offers ${listed.size} models and does not say which is " +
                    "loaded; name one in Settings"
            )
        }
    }

    /** One chat completion, retried once when the answer holds no balanced JSON object. */
    private fun generate(
        baseUrl: String,
        apiKey: String,
        model: String,
        prompt: String,
        maxTokens: Int,
        grammar: String?,
    ): String {
        fun request(strict: String): String {
            val body = JSONObject()
                .put("model", model)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", strict + prompt)))
                .put("max_tokens", maxTokens)
                .put("temperature", 0.0)
                .put("chat_template_kwargs", JSONObject().put("thinking", false).put("enable_thinking", false))
                .apply { grammar?.let { put("grammar", it) } }
                .toString()
            val response = ServerHttp.postJson(
                "$baseUrl/v1/chat/completions", body, apiKey,
                // A merge over several chunks on a busy home server can think for a while.
                readTimeoutMs = 10 * 60 * 1000,
                connection = connection,
            )
            if (aborted.get()) throw InterruptedException("Summary stopped")
            val message = JSONObject(response.body)
                .optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ?: throw IllegalStateException("Server returned no completion")
            return pickText(message)
        }

        val first = request("")
        extractJson(first)?.let { return it }
        AppLogger.w(TAG, "The answer held no JSON object; trying once with a harder instruction")
        val second = request("Output ONLY a valid JSON object. No prose, no code fences.\n\n")
        return extractJson(second) ?: second
    }
}
