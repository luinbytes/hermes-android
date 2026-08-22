package com.nousresearch.hermes.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.nousresearch.hermes.protocol.ModelCapabilities
import com.nousresearch.hermes.ui.theme.HermesSkin
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PrivacyPreferencesTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `client preferences are durable and isolated`() = runTest {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val file = File(temporaryFolder.root, "privacy.preferences_pb")
        val store = PreferenceDataStoreFactory.create(scope = scope) { file }
        val preferences = PrivacyPreferences(store)

        assertFalse(preferences.secureScreen.first())
        assertFalse(preferences.botModeEnabled.first())
        assertEquals(HermesSkin.NOUS, preferences.skin.first())
        preferences.setSecureScreen(true)
        assertTrue(preferences.secureScreen.first())
        preferences.setSecureScreen(false)
        assertFalse(preferences.secureScreen.first())
        preferences.setBotModeEnabled(true)
        assertTrue(preferences.botModeEnabled.first())
        assertTrue(PrivacyPreferences(store).botModeEnabled.first())
        preferences.setSkin(HermesSkin.EMBER)
        assertEquals(HermesSkin.EMBER, preferences.skin.first())
        assertEquals(ModelPreset(), preferences.modelPreset("nous", "hermes-4"))
        preferences.setModelReasoningPreset("nous", "hermes-4", "high")
        preferences.setModelFastPreset("nous", "hermes-4", true)
        assertEquals(ModelPreset(effort = "high", fast = true), preferences.modelPreset("nous", "hermes-4"))
        assertEquals(ModelPreset(), preferences.modelPreset("nous", "hermes-4-fast"))
        assertTrue(runCatching { preferences.setModelReasoningPreset("nous", "hermes-4", "unsupported") }.isFailure)
        assertEquals(
            listOf("reasoning" to "high"),
            ModelPreset(effort = "high", fast = true).sessionConfigChanges(ModelCapabilities(reasoning = true)),
        )
        assertEquals(
            listOf("reasoning" to "high", "fast" to "normal"),
            ModelPreset(effort = "high", fast = false).sessionConfigChanges(ModelCapabilities(reasoning = true, fast = true)),
        )
        scope.cancel()
    }

    @Test
    fun `biometric re-entry is opt-in and durable`() = runTest {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val file = File(temporaryFolder.root, "biometric.preferences_pb")
        val store = PreferenceDataStoreFactory.create(scope = scope) { file }
        val preferences = PrivacyPreferences(store)

        assertFalse(preferences.biometricReentry.first())
        preferences.setBiometricReentry(true)
        assertTrue(preferences.biometricReentry.first())

        val recreated = PrivacyPreferences(store)
        assertTrue(recreated.biometricReentry.first())
        recreated.setBiometricReentry(false)
        assertFalse(preferences.biometricReentry.first())
        scope.cancel()
    }

    @Test
    fun `biometric re-entry fails closed when preferences cannot be read`() = runTest {
        val failingStore = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow {
                throw IOException("privacy preferences unavailable")
            }

            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                error("not used")
        }

        assertTrue(PrivacyPreferences(failingStore).biometricReentry.first())
    }
}
