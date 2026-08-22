@file:Suppress("DEPRECATION") // API 28 needs the platform fingerprint and credential compatibility paths.

package com.nousresearch.hermes

import android.app.Activity
import android.content.Intent
import android.app.KeyguardManager
import android.hardware.biometrics.BiometricPrompt
import android.hardware.fingerprint.FingerprintManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.ReportDrawnWhen
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.nousresearch.hermes.data.PrivacyPreferences
import com.nousresearch.hermes.platform.HermesEntryRequestStore
import com.nousresearch.hermes.platform.parseHermesEntryRequest
import com.nousresearch.hermes.platform.publishPrivacySafeShortcuts
import com.nousresearch.hermes.ui.HermesApp
import com.nousresearch.hermes.ui.BiometricLockScreen
import com.nousresearch.hermes.ui.PrivacyGateViewModel
import com.nousresearch.hermes.ui.theme.HermesSkin
import com.nousresearch.hermes.ui.theme.HermesTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var privacyPreferences: PrivacyPreferences
    @Inject lateinit var entryRequestStore: HermesEntryRequestStore
    private var workspaceReady by mutableStateOf(false)
    private val privacyGate: PrivacyGateViewModel by viewModels()
    private var biometricPromptActive = false
    private var biometricCancellation: CancellationSignal? = null
    private val credentialLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            privacyGate.unlock()
        } else {
            privacyGate.authenticationError("Device credential authentication was cancelled.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) publishEntryRequest(intent)
        runCatching { publishPrivacySafeShortcuts(this) }
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        lifecycleScope.launch {
            privacyPreferences.secureScreen.collect { enabled ->
                if (enabled) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }
        setContent {
            val secureScreen by privacyPreferences.secureScreen.collectAsStateWithLifecycle(initialValue = false)
            val biometricReentryFlow = remember {
                privacyPreferences.biometricReentry.map<Boolean, Boolean?> { it }
            }
            val biometricReentry by biometricReentryFlow.collectAsStateWithLifecycle(initialValue = null)
            val skin by privacyPreferences.skin.collectAsStateWithLifecycle(initialValue = HermesSkin.NOUS)
            val botModeEnabled by privacyPreferences.botModeEnabled.collectAsStateWithLifecycle(initialValue = false)
            val entryRequests by entryRequestStore.deliveries.collectAsStateWithLifecycle()
            val biometricAvailable = authenticationAvailable()
            val locked = biometricReentry == true && privacyGate.isLocked(enabled = true)
            ReportDrawnWhen {
                biometricReentry != null && (locked || workspaceReady)
            }
            when {
                biometricReentry == null -> HermesTheme(skin) { }
                locked -> {
                    HermesTheme(skin) {
                        BiometricLockScreen(privacyGate.error, ::authenticate, ::useDeviceCredential)
                    }
                    LaunchedEffect(Unit) { authenticate() }
                }
                else -> HermesApp(
                    secureScreen = secureScreen,
                    onSecureScreenChange = { enabled ->
                        lifecycleScope.launch { privacyPreferences.setSecureScreen(enabled) }
                    },
                    biometricReentry = biometricReentry == true,
                    biometricAvailable = biometricAvailable,
                    onBiometricReentryChange = { enabled ->
                        if (!enabled || biometricAvailable) {
                            if (enabled) privacyGate.lock()
                            lifecycleScope.launch { privacyPreferences.setBiometricReentry(enabled) }
                        }
                    },
                    skin = skin,
                    onSkinChange = { selected ->
                        lifecycleScope.launch { privacyPreferences.setSkin(selected) }
                    },
                    botModeEnabled = botModeEnabled,
                    onBotModeEnabledChange = { enabled ->
                        lifecycleScope.launch { privacyPreferences.setBotModeEnabled(enabled) }
                    },
                    entryDelivery = entryRequests.firstOrNull(),
                    onWorkspaceReady = { workspaceReady = true },
                    onEntryConsumed = ::consumeEntryRequest,
                    onEntryFailed = entryRequestStore::fail,
                    onEntryRetry = entryRequestStore::retry,
                    onEntryDiscard = ::discardEntryRequest,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        privacyGate.onForeground(SystemClock.elapsedRealtime())
    }

    override fun onStop() {
        biometricCancellation?.cancel()
        biometricCancellation = null
        biometricPromptActive = false
        privacyGate.onBackground(SystemClock.elapsedRealtime(), isChangingConfigurations)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        publishEntryRequest(intent)
    }

    private fun publishEntryRequest(intent: Intent?) {
        parseHermesEntryRequest(intent, packageName)?.let(entryRequestStore::enqueue)
        // Clear accepted and rejected external payloads alike. The process-private
        // store owns valid delivery; malformed extras must not survive task restore.
        setIntent(Intent(this, MainActivity::class.java))
    }

    private fun consumeEntryRequest(id: String) {
        entryRequestStore.consume(id)
        clearDeliveredIntent()
    }

    private fun discardEntryRequest(id: String) {
        entryRequestStore.discard(id)
        clearDeliveredIntent()
    }

    private fun clearDeliveredIntent() {
        if (entryRequestStore.deliveries.value.isEmpty()) {
            setIntent(Intent(this, MainActivity::class.java))
        }
    }

    private fun authenticate() {
        if (biometricPromptActive || isFinishing) return
        if (!authenticationAvailable()) {
            privacyGate.authenticationError("Set a device screen lock before opening protected Hermes content.")
            return
        }
        biometricPromptActive = true
        privacyGate.authenticationError(null)
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.P && !fingerprintAvailable()) {
            biometricPromptActive = false
            useDeviceCredential()
            return
        }
        val cancellation = CancellationSignal().also { biometricCancellation = it }
        val builder = BiometricPrompt.Builder(this)
            .setTitle("Unlock Hermes")
            .setSubtitle("Authenticate to open protected Hermes content")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setDeviceCredentialAllowed(true)
        } else {
            builder.setNegativeButton("Use device credential", mainExecutor) { _, _ ->
                biometricPromptActive = false
                biometricCancellation = null
                useDeviceCredential()
            }
        }
        val prompt = builder.build()
        runCatching {
            prompt.authenticate(
                cancellation,
                mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        biometricPromptActive = false
                        biometricCancellation = null
                        privacyGate.unlock()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        biometricPromptActive = false
                        biometricCancellation = null
                        if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_CANCELED) {
                            privacyGate.authenticationError(errString.toString())
                        }
                    }

                    override fun onAuthenticationFailed() {
                        privacyGate.authenticationError("Biometric authentication was not recognized. Try again.")
                    }
                },
            )
        }.onFailure { error ->
            biometricPromptActive = false
            biometricCancellation = null
            privacyGate.authenticationError(error.message ?: "Android could not start authentication.")
        }
    }

    private fun authenticationAvailable(): Boolean =
        getSystemService(KeyguardManager::class.java).isDeviceSecure

    private fun fingerprintAvailable(): Boolean =
        getSystemService(FingerprintManager::class.java).let {
            it.isHardwareDetected && it.hasEnrolledFingerprints()
        }

    private fun useDeviceCredential() {
        biometricCancellation?.cancel()
        biometricCancellation = null
        biometricPromptActive = false
        val intent = getSystemService(KeyguardManager::class.java).createConfirmDeviceCredentialIntent(
            "Unlock Hermes",
            "Authenticate to open protected Hermes content",
        )
        if (intent == null) {
            privacyGate.authenticationError("Set a device screen lock before opening protected Hermes content.")
        } else {
            credentialLauncher.launch(intent)
        }
    }
}
