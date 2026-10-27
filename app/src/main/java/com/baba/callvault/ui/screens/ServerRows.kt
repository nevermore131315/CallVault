/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.baba.callvault.R
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.ui.viewmodels.SettingsActions

/**
 * The server rows for both backends, shared by the classic screen and the new one.
 *
 * **A switch plus plain text fields, not a model picker.** A server backend is defined by a URL;
 * there is no catalogue of choices to dropdown, and inventing one would be inventing a protocol
 * the app does not have. The fields appear under the switch rather than always on screen, so a
 * phone that does transcription on its own is never shown a hostname box it will not fill.
 *
 * Nothing here sends traffic the moment it is typed: values are saved as typed, requests happen
 * only when a job runs.
 */

@Composable
internal fun TranscriptionServerRows(
    preferences: AppPreferences,
    updateTrigger: Int,
    actions: SettingsActions,
) {
    val enabled = remember(updateTrigger) { preferences.isServerAsrEnabled() }

    SettingsToggleRow(
        label = stringResource(R.string.server_asr_switch),
        checked = enabled,
        onCheckedChange = actions::setServerAsrEnabled,
        icon = Icons.Filled.Cloud,
        description = stringResource(R.string.server_asr_switch_description),
        descriptionBelow = true,
    )

    if (enabled) {
        ServerTextRow(
            label = stringResource(R.string.server_url_label),
            value = remember(updateTrigger) { preferences.getServerAsrUrl() },
            onValueChange = actions::setServerAsrUrl,
            placeholder = "http://192.168.1.10:8098",
            supporting = stringResource(R.string.server_url_support),
        )
        ServerTextRow(
            label = stringResource(R.string.server_key_label),
            value = remember(updateTrigger) { preferences.getServerAsrKey() },
            onValueChange = actions::setServerAsrKey,
            placeholder = stringResource(R.string.server_key_placeholder),
        )
    }
}

@Composable
internal fun SummaryServerRows(
    preferences: AppPreferences,
    updateTrigger: Int,
    actions: SettingsActions,
) {
    val enabled = remember(updateTrigger) { preferences.isServerSummaryEnabled() }

    SettingsToggleRow(
        label = stringResource(R.string.server_summary_switch),
        checked = enabled,
        onCheckedChange = actions::setServerSummaryEnabled,
        icon = Icons.Filled.Cloud,
        description = stringResource(R.string.server_summary_switch_description),
        descriptionBelow = true,
    )

    if (enabled) {
        ServerTextRow(
            label = stringResource(R.string.server_url_label),
            value = remember(updateTrigger) { preferences.getServerSummaryUrl() },
            onValueChange = actions::setServerSummaryUrl,
            placeholder = "http://192.168.1.10:8091",
            supporting = stringResource(R.string.server_url_support),
        )
        ServerTextRow(
            label = stringResource(R.string.server_key_label),
            value = remember(updateTrigger) { preferences.getServerSummaryKey() },
            onValueChange = actions::setServerSummaryKey,
            placeholder = stringResource(R.string.server_key_placeholder),
        )
        ServerTextRow(
            label = stringResource(R.string.server_model_label),
            value = remember(updateTrigger) { preferences.getServerSummaryModel() },
            onValueChange = actions::setServerSummaryModel,
            placeholder = stringResource(R.string.server_model_placeholder),
            supporting = stringResource(R.string.server_model_support),
        )
    }
}

/**
 * One labelled text row inside a settings card. `OutlinedTextField` rather than a dialog opened
 * from a row: a base URL is something people paste and check, and a dialog hides what is actually
 * stored.
 */
@Composable
private fun ServerTextRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    supporting: String? = null,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            placeholder = { Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        if (supporting != null) {
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
