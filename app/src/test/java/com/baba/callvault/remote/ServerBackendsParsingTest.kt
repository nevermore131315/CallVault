/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.remote

import com.baba.callvault.transcription.TranscriptSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The two pure parsing seams of the server backends — where an arbitrary LAN server's JSON meets
 * CallVault's transcript and summary pipelines.
 *
 * The rule both sides of the fork agree on: **words survive, shape does not gatekeep them.** A
 * server that answers in a slightly different shape still gets its words into the transcript,
 * degrading timestamps before it degrades text; a summary wrapped in markdown fences or thinking
 * prose still yields its object. Only genuinely empty or genuinely malformed input comes back
 * empty or null, because an invented timestamp or a fabricated summary would be worse than an
 * honest gap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ServerBackendsParsingTest {

    // --- RemoteTranscriber.parse -------------------------------------------------------------

    @Test
    fun `millisecond segments are taken exactly`() {
        val body = """{"text":"a b","segments":[
            {"start_ms":0,"end_ms":1200,"text":"Hello there"},
            {"start_ms":1500,"end_ms":3000,"text":"Answering"}]}"""
        val got = RemoteTranscriber.parse(body, 3_000L)
        assertEquals(
            listOf(
                TranscriptSegment(0L, 1_200L, "Hello there"),
                TranscriptSegment(1_500L, 3_000L, "Answering"),
            ),
            got
        )
    }

    @Test
    fun `OpenAI verbose_json seconds become milliseconds`() {
        val body = """{"segments":[{"start":1.5,"end":2.25,"text":"Half a second in"}]}"""
        val got = RemoteTranscriber.parse(body, 5_000L)
        assertEquals(1, got.size)
        assertEquals(1_500L, got[0].startMs)
        assertEquals(2_250L, got[0].endMs)
    }

    @Test
    fun `segments with empty text are dropped, negative starts clamped`() {
        val body = """{"segments":[
            {"start_ms":-40,"end_ms":900,"text":" lead in "},
            {"start_ms":900,"end_ms":1000,"text":"   "}]}"""
        val got = RemoteTranscriber.parse(body, 1_000L)
        assertEquals(1, got.size)
        assertEquals(0L, got[0].startMs)
        assertEquals("lead in", got[0].text)
    }

    @Test
    fun `plain text becomes one segment across the recording, not a failure`() {
        val got = RemoteTranscriber.parse("""{"text":"Whole call, no timestamps."}""", 61_000L)
        assertEquals(listOf(TranscriptSegment(0L, 61_000L, "Whole call, no timestamps.")), got)
    }

    @Test
    fun `garbage answers yield no segments rather than throwing`() {
        assertEquals(emptyList<TranscriptSegment>(), RemoteTranscriber.parse("not json at all", 10L))
        assertEquals(emptyList<TranscriptSegment>(), RemoteTranscriber.parse("""{"text":""}""", 10L))
    }

    // --- RemoteSummaryHost.extractJson -------------------------------------------------------

    @Test
    fun `a bare object passes through`() {
        assertEquals("""{"summary":"x"}""", RemoteSummaryHost.extractJson("""{"summary":"x"}"""))
    }

    @Test
    fun `markdown fences and a thinking preamble are stripped`() {
        val text = "Let me think about this call.\n\n```json\n{\"summary\":\"kept\"}\n```\nDone."
        assertEquals("""{"summary":"kept"}""", RemoteSummaryHost.extractJson(text))
    }

    @Test
    fun `braces inside strings and escapes do not end the object early`() {
        val raw = """{"summary":"a {quoted \"inner\"} phrase","items":["}"]} trailing prose {"""
        assertEquals(
            """{"summary":"a {quoted \"inner\"} phrase","items":["}"]}""",
            RemoteSummaryHost.extractJson(raw)
        )
    }

    @Test
    fun `nested objects end at the outer brace, not the inner one`() {
        val raw = """{"a":{"b":1},"c":2}"""
        assertEquals(raw, RemoteSummaryHost.extractJson(raw))
    }

    @Test
    fun `prose with no object at all is null, honestly`() {
        assertNull(RemoteSummaryHost.extractJson("There is nothing structured here."))
    }

    // --- RemoteSummaryHost.pickText ----------------------------------------------------------

    @Test
    fun `content wins when present`() {
        val message = JSONObject("""{"content":"the answer","reasoning_content":"the monologue"}""")
        assertEquals("the answer", RemoteSummaryHost.pickText(message))
    }

    @Test
    fun `a thinking model that answered only in reasoning still yields its text`() {
        val message = JSONObject("""{"content":null,"reasoning_content":"the monologue {\"a\":1}"}""")
        assertEquals("""the monologue {"a":1}""", RemoteSummaryHost.pickText(message))
    }

    @Test
    fun `an empty completion is an error, not an empty summary`() {
        val message = JSONObject("""{"content":"","reasoning_content":""}""")
        assertThrows(IllegalStateException::class.java) { RemoteSummaryHost.pickText(message) }
    }
}
