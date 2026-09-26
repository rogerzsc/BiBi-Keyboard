/**
 * HomeRail 直达设置页（fork 新增）。
 *
 * 归属模块：ui/settings/compose/screens
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.screens

import android.widget.Toast
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.brycewg.asrkb.R
import com.brycewg.asrkb.homerail.HomeRailDirect
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.settings.compose.components.SettingsActionButton
import com.brycewg.asrkb.ui.settings.compose.components.SettingsActionButtonRow
import com.brycewg.asrkb.ui.settings.compose.components.SettingsDetailScaffold
import com.brycewg.asrkb.ui.settings.compose.components.SettingsLazyColumn
import com.brycewg.asrkb.ui.settings.compose.components.SettingsPreference
import com.brycewg.asrkb.ui.settings.compose.components.SettingsSectionContainer
import com.brycewg.asrkb.ui.settings.compose.components.SettingsTextField
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.model.SettingsEntry
import kotlinx.coroutines.launch

@Composable
fun HomeRailSettingsScreen(
    uiMode: BibiUiMode,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember(context) { Prefs(context) }
    val coroutineScope = rememberCoroutineScope()
    var directEnabled by remember { mutableStateOf(prefs.homerailDirectEnabled) }
    var baseUrl by remember { mutableStateOf(prefs.homerailBaseUrl) }
    var password by remember { mutableStateOf(prefs.homerailPassword) }
    var ttsEnabled by remember { mutableStateOf(prefs.homerailTtsEnabled) }
    var testing by remember { mutableStateOf(false) }

    SettingsDetailScaffold(
        uiMode = uiMode,
        titleRes = R.string.homerail_settings_title,
        onBack = onBack
    ) { padding, modifier ->
        SettingsLazyColumn(
            uiMode = uiMode,
            modifier = modifier.padding(padding)
        ) {
            item {
                SettingsSectionContainer(uiMode = uiMode) {
                    SettingsPreference(
                        entry = SettingsEntry.Switch(
                            id = "homerail_direct_enabled",
                            titleRes = R.string.homerail_direct_enabled,
                            summary = null,
                            checked = directEnabled,
                            onCheckedChange = { value ->
                                directEnabled = value
                                prefs.homerailDirectEnabled = value
                            }
                        )
                    )
                    SettingsTextField(
                        uiMode = uiMode,
                        value = baseUrl,
                        onValueChange = { value ->
                            baseUrl = value
                            prefs.homerailBaseUrl = value
                        },
                        label = context.getString(R.string.homerail_base_url_label),
                        placeholder = "https://117.50.71.186",
                        index = 1,
                        count = 4
                    )
                    SettingsTextField(
                        uiMode = uiMode,
                        value = password,
                        onValueChange = { value ->
                            password = value
                            prefs.homerailPassword = value
                        },
                        label = context.getString(R.string.homerail_password_label),
                        password = true,
                        index = 2,
                        count = 4
                    )
                    SettingsPreference(
                        entry = SettingsEntry.Switch(
                            id = "homerail_tts_enabled",
                            titleRes = R.string.homerail_tts_enabled,
                            summary = null,
                            checked = ttsEnabled,
                            onCheckedChange = { value ->
                                ttsEnabled = value
                                prefs.homerailTtsEnabled = value
                            }
                        ),
                        index = 3,
                        count = 4
                    )
                }
            }
            item {
                SettingsActionButtonRow(uiMode = uiMode) {
                    SettingsActionButton(
                        uiMode = uiMode,
                        text = context.getString(R.string.homerail_test_connection),
                        enabled = !testing,
                        onClick = {
                            testing = true
                            coroutineScope.launch {
                                val result = HomeRailDirect.testConnection(prefs)
                                testing = false
                                Toast.makeText(context, result, Toast.LENGTH_LONG).show()
                            }
                        }
                    )
                    SettingsActionButton(
                        uiMode = uiMode,
                        text = context.getString(R.string.homerail_reset_session),
                        onClick = {
                            prefs.homerailSessionId = ""
                            Toast.makeText(
                                context,
                                context.getString(R.string.homerail_session_reset_done),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    )
                }
            }
        }
    }
}
