package com.nousresearch.hermes.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nousresearch.hermes.platform.HermesNotificationPermission
import com.nousresearch.hermes.platform.hermesNotificationPermission
import com.nousresearch.hermes.platform.markHermesNotificationPermissionRequested
import com.nousresearch.hermes.ui.theme.HermesSkin

@Composable
@SuppressLint("InlinedApi") // POST_NOTIFICATIONS is an inlined permission string and only launched after the API-aware policy requests it.
internal fun AppSettingsScreen(
    secureScreen: Boolean,
    onSecureScreenChange: (Boolean) -> Unit,
    biometricReentry: Boolean,
    biometricAvailable: Boolean,
    onBiometricReentryChange: (Boolean) -> Unit,
    skin: HermesSkin,
    onSkinChange: (HermesSkin) -> Unit,
    botModeEnabled: Boolean,
    onBotModeEnabledChange: (Boolean) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var notificationPermission by remember { mutableStateOf(hermesNotificationPermission(context)) }
    val requestNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationPermission = hermesNotificationPermission(context)
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationPermission = hermesNotificationPermission(context)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            onBack?.let {
                IconButton(onClick = it) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(
                    "APP SETTINGS",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                Text("Appearance and privacy on this device", style = MaterialTheme.typography.bodySmall)
            }
        }
        HorizontalDivider()
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { AppearancePicker(skin, onSkinChange) }
            item {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("BOT MODE", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Open Chats as a messaging roster of persistent Hermes agents. Sessions remain available beside Bots.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = botModeEnabled,
                            onCheckedChange = onBotModeEnabledChange,
                            modifier = Modifier.semantics { contentDescription = "Bot Mode" },
                        )
                    }
                }
            }
            item {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("SECURE SCREEN", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Block screenshots, screen recording and the recent-app thumbnail for Hermes content on this device.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = secureScreen,
                            onCheckedChange = onSecureScreenChange,
                            modifier = Modifier.semantics { contentDescription = "Secure screen" },
                        )
                    }
                }
            }
            item {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("BIOMETRIC RE-ENTRY", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (biometricAvailable) {
                                    "Require biometrics or the device credential after Hermes stays in the background for five minutes."
                                } else {
                                    "Set a device screen lock before enabling biometric re-entry."
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = biometricReentry,
                            onCheckedChange = onBiometricReentryChange,
                            enabled = biometricAvailable,
                            modifier = Modifier.semantics { contentDescription = "Biometric re-entry" },
                        )
                    }
                }
            }
            item {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                    Column(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "NOTIFICATIONS",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.semantics { heading() },
                        )
                        Text(
                            if (notificationPermission == HermesNotificationPermission.GRANTED) "Allowed" else "Not allowed",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "Allow completion, action-required, automation failure and cron result alerts. Message content stays private.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (notificationPermission != HermesNotificationPermission.GRANTED) {
                            OutlinedButton(
                                onClick = {
                                    if (notificationPermission == HermesNotificationPermission.REQUEST) {
                                        markHermesNotificationPermissionRequested(context)
                                        requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    } else {
                                        context.startActivity(
                                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    if (notificationPermission == HermesNotificationPermission.REQUEST) {
                                        "ALLOW NOTIFICATIONS"
                                    } else {
                                        "OPEN NOTIFICATION SETTINGS"
                                    },
                                )
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    "These preferences affect only this Android device. Hermes server configuration lives under Manage.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
