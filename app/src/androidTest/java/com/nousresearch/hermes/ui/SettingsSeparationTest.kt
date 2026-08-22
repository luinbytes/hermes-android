package com.nousresearch.hermes.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import com.nousresearch.hermes.data.HermesState
import com.nousresearch.hermes.protocol.GatewayConnectionState
import com.nousresearch.hermes.ui.theme.HermesSkin
import com.nousresearch.hermes.ui.theme.HermesTheme
import org.junit.Rule
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSeparationTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun appSettingsOwnDevicePreferences() {
        compose.setContent {
            HermesTheme {
                AppSettingsScreen(
                    secureScreen = true,
                    onSecureScreenChange = {},
                    biometricReentry = true,
                    biometricAvailable = true,
                    onBiometricReentryChange = {},
                    skin = HermesSkin.NOUS,
                    onSkinChange = {},
                    botModeEnabled = true,
                    onBotModeEnabledChange = {},
                    onBack = null,
                )
            }
        }

        compose.onNodeWithText("APP SETTINGS").assertExists()
        compose.onNodeWithText("APPEARANCE").assertExists()
        compose.onNode(hasScrollAction()).performScrollToIndex(1)
        compose.onNodeWithText("SECURE SCREEN").assertExists()
        compose.onNode(hasScrollAction()).performScrollToIndex(2)
        compose.onNodeWithText("BIOMETRIC RE-ENTRY").assertExists()
        compose.onNode(hasScrollAction()).performScrollToIndex(3)
        compose.onNodeWithText("NOTIFICATIONS").assertExists()
        compose.onNodeWithText("Message content stays private.", substring = true).assertExists()
    }

    @Test
    fun biometricGateExposesErrorAndExplicitRetry() {
        var retried = false
        var usedCredential = false
        compose.setContent {
            HermesTheme {
                BiometricLockScreen(
                    error = "Authentication cancelled.",
                    onUnlock = { retried = true },
                    onUseDeviceCredential = { usedCredential = true },
                )
            }
        }

        compose.onNodeWithText("HERMES LOCKED").assertExists()
        compose.onNodeWithText("Authentication cancelled.").assertExists()
        compose.onNodeWithText("UNLOCK").performClick()
        assertTrue(retried)
        compose.onNodeWithText("USE DEVICE CREDENTIAL").performClick()
        assertTrue(usedCredential)
    }

    @Test
    fun remoteDiagnosticsDoNotExposeDevicePreferences() {
        compose.setContent {
            HermesTheme {
                DiagnosticsScreen(
                    state = HermesState(),
                    connection = GatewayConnectionState.Idle,
                    onRun = {},
                    onRefreshHost = {},
                    backup = HostBackupUiState(),
                    onPrepareBackup = {},
                    onSaveBackup = {},
                    onCancelBackup = {},
                    onBack = null,
                )
            }
        }

        compose.onNodeWithText("DIAGNOSTICS").assertExists()
        compose.onNodeWithText("APPEARANCE").assertDoesNotExist()
        compose.onNodeWithText("SECURE SCREEN").assertDoesNotExist()
        compose.onNode(hasScrollAction()).performScrollToIndex(2)
        compose.onNodeWithText("Create backup").performClick()
        compose.onNodeWithText("CREATE HERMES HOST BACKUP?").assertExists()
        compose.onNodeWithText("not only profile default", substring = true).assertExists()
    }

    @Test
    fun scopedDestinationKeepsStableResourceIdentityVisible() {
        compose.setContent {
            HermesTheme {
                ScopedDestinationScreen(
                    title = "Cron",
                    resourceId = "daily-review",
                    onBack = {},
                )
            }
        }

        compose.onNodeWithText("Scoped Hermes resource").assertExists()
        compose.onNodeWithText("daily-review").assertExists()
    }
}
