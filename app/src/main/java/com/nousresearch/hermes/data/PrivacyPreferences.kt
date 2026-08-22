package com.nousresearch.hermes.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nousresearch.hermes.protocol.ModelCapabilities
import com.nousresearch.hermes.ui.theme.HermesSkin
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.privacyDataStore by preferencesDataStore("hermes_privacy")

internal val VALID_REASONING_EFFORTS = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra")

internal data class ModelPreset(
    val effort: String? = null,
    val fast: Boolean? = null,
) {
    fun sessionConfigChanges(capabilities: ModelCapabilities): List<Pair<String, String>> = buildList {
        if (capabilities.reasoning) effort?.let { add("reasoning" to it) }
        if (capabilities.fast) fast?.let { add("fast" to if (it) "fast" else "normal") }
    }
}

@Singleton
class PrivacyPreferences internal constructor(
    private val store: DataStore<Preferences>,
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context.privacyDataStore)

    val secureScreen: Flow<Boolean> = store.data
        .map { it[SECURE_SCREEN] ?: false }
        .catch { emit(true) }

    val biometricReentry: Flow<Boolean> = store.data
        .map { it[BIOMETRIC_REENTRY] ?: false }
        .catch { emit(true) }

    val botModeEnabled: Flow<Boolean> = store.data
        .map { it[BOT_MODE_ENABLED] ?: false }
        .catch { emit(false) }

    val skin: Flow<HermesSkin> = store.data
        .map { HermesSkin.fromId(it[SKIN]) }
        .catch { emit(HermesSkin.NOUS) }

    suspend fun setSecureScreen(enabled: Boolean) {
        store.edit { it[SECURE_SCREEN] = enabled }
    }

    suspend fun setBiometricReentry(enabled: Boolean) {
        store.edit { it[BIOMETRIC_REENTRY] = enabled }
    }

    suspend fun setBotModeEnabled(enabled: Boolean) {
        store.edit { it[BOT_MODE_ENABLED] = enabled }
    }

    suspend fun setSkin(skin: HermesSkin) {
        store.edit { it[SKIN] = skin.id }
    }

    internal suspend fun modelPreset(provider: String, model: String): ModelPreset {
        val prefix = modelPresetPrefix(provider, model)
        val preferences = store.data.first()
        return ModelPreset(
            effort = preferences[stringPreferencesKey("$prefix.effort")]?.takeIf { it in VALID_REASONING_EFFORTS },
            fast = preferences[stringPreferencesKey("$prefix.fast")]?.toBooleanStrictOrNull(),
        )
    }

    internal suspend fun setModelReasoningPreset(provider: String, model: String, effort: String) {
        require(effort in VALID_REASONING_EFFORTS) { "Unsupported Hermes reasoning effort" }
        store.edit { it[stringPreferencesKey("${modelPresetPrefix(provider, model)}.effort")] = effort }
    }

    internal suspend fun setModelFastPreset(provider: String, model: String, enabled: Boolean) {
        store.edit { it[stringPreferencesKey("${modelPresetPrefix(provider, model)}.fast")] = enabled.toString() }
    }

    private fun modelPresetPrefix(provider: String, model: String): String {
        ModelSelection(provider, model).rpcValue()
        return "model_preset.v1.$provider::$model"
    }

    private companion object {
        val SECURE_SCREEN = booleanPreferencesKey("secure_screen")
        val BIOMETRIC_REENTRY = booleanPreferencesKey("biometric_reentry")
        val BOT_MODE_ENABLED = booleanPreferencesKey("bot_mode_enabled")
        val SKIN = stringPreferencesKey("skin")
    }
}
