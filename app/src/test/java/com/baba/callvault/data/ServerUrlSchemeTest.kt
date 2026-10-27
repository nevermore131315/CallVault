/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A typed server address without a scheme must still work.
 *
 * The failure this guards is not cosmetic: `100.123.202.29:8098` makes `URL()` read everything
 * before the colon as the protocol, and a protocol may not start with a digit, so a run dies with
 * "no protocol" after the recording has already been decoded and read. People type addresses that
 * way; the app has to meet them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ServerUrlSchemeTest {

    private val prefs = AppPreferences(ApplicationProvider.getApplicationContext())

    @Test
    fun `a bare host and port reads as http`() {
        prefs.setServerAsrUrl("100.123.202.29:8098/")
        assertEquals("http://100.123.202.29:8098", prefs.getServerAsrUrl())
    }

    @Test
    fun `a typed scheme is kept exactly`() {
        prefs.setServerAsrUrl("https://box.lan:8098")
        assertEquals("https://box.lan:8098", prefs.getServerAsrUrl())
        prefs.setServerAsrUrl("http://192.168.1.10:8098")
        assertEquals("http://192.168.1.10:8098", prefs.getServerAsrUrl())
    }

    @Test
    fun `the summary address is normalised the same way`() {
        prefs.setServerSummaryUrl(" box.lan:8091 ")
        assertEquals("http://box.lan:8091", prefs.getServerSummaryUrl())
    }

    @Test
    fun `a blank address stays blank, so it still means disabled`() {
        prefs.setServerAsrUrl("   ")
        assertEquals("", prefs.getServerAsrUrl())
        prefs.setServerSummaryEnabled(true)
        prefs.setServerSummaryUrl("")
        assertEquals("", prefs.getServerSummaryUrl())
        assertEquals(false, prefs.useServerSummary())
    }
}
