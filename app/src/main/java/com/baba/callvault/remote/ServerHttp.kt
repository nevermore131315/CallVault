/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.remote

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference

/**
 * The HTTP the server backends need: GET, POST, and a multipart file upload.
 *
 * `HttpURLConnection` rather than a client library, because that is what the rest of the app
 * already speaks (release checks and model downloads) and adding OkHttp for three requests would
 * be a dependency the app has so far kept out deliberately.
 *
 * Every call hands back an [HttpURLConnection] reference through a caller-supplied
 * [AtomicReference] so a stop request can drop the socket mid-read — the engine's abort path is
 * a user tapping Stop, and "the request finishes anyway, several minutes later" is not stopping.
 *
 * Error messages carry a short slice of the server's body because "the server said 500" is
 * unactionable, but the slice is deliberately tiny: a server echoing request content back could
 * otherwise put the words of someone's private call into the log, which is exactly what
 * [com.baba.callvault.summary.SummaryEngine] refuses to do.
 */
internal object ServerHttp {

    class Response(val status: Int, val body: String)

    private const val ERROR_BODY_MAX = 160

    fun get(
        url: String,
        apiKey: String,
        readTimeoutMs: Int = 15_000,
        connection: AtomicReference<HttpURLConnection>? = null,
    ): Response = execute(url, "GET", null, null, apiKey, readTimeoutMs, connection)

    fun postJson(
        url: String,
        json: String,
        apiKey: String,
        readTimeoutMs: Int = 300_000,
        connection: AtomicReference<HttpURLConnection>? = null,
    ): Response = execute(
        url, "POST", json.toByteArray(Charsets.UTF_8), "application/json",
        apiKey, readTimeoutMs, connection,
    )

    /**
     * POSTs a multipart/form-data body with one file part — the OpenAI `/v1/audio/transcriptions`
     * shape, which is what ASR servers on a LAN expect.
     */
    fun postMultipartFile(
        url: String,
        fields: List<Pair<String, String>>,
        fileBytes: ByteArray,
        fileName: String,
        fileContentType: String,
        apiKey: String,
        readTimeoutMs: Int = 300_000,
        connection: AtomicReference<HttpURLConnection>? = null,
    ): Response {
        val boundary = "----callvault" + System.nanoTime().toString(16)
        val out = ByteArrayOutputStream(fileBytes.size + 1024)
        out.write("--$boundary\r\n".toByteArray())
        for ((name, value) in fields) {
            out.write(
                "Content-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n--$boundary\r\n"
                    .toByteArray()
            )
        }
        out.write(
            ("Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n" +
                "Content-Type: $fileContentType\r\n\r\n").toByteArray()
        )
        out.write(fileBytes)
        out.write("\r\n--$boundary--\r\n".toByteArray())
        return execute(
            url, "POST", out.toByteArray(), "multipart/form-data; boundary=$boundary",
            apiKey, readTimeoutMs, connection,
        )
    }

    private fun execute(
        url: String,
        method: String,
        body: ByteArray?,
        contentType: String?,
        apiKey: String,
        readTimeoutMs: Int,
        connection: AtomicReference<HttpURLConnection>?,
    ): Response {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            this.readTimeout = readTimeoutMs
            doInput = true
            if (body != null) {
                doOutput = true
                setFixedLengthStreamingMode(body.size)
                contentType?.let { setRequestProperty("Content-Type", it) }
            }
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Accept", "application/json")
        }
        connection?.set(conn)
        try {
            if (body != null) conn.outputStream.use { it.write(body) }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            if (status !in 200..299) {
                throw IOException("HTTP $status from $url: ${text.take(ERROR_BODY_MAX)}")
            }
            return Response(status, text)
        } finally {
            connection?.compareAndSet(conn, null)
            conn.disconnect()
        }
    }
}
