package com.nousresearch.hermes.platform

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.nousresearch.hermes.R
import com.nousresearch.hermes.ui.navigation.HermesDestinationRoute

enum class HermesNotificationPermission { GRANTED, REQUEST, SETTINGS }

enum class HermesNotificationKind(
    internal val channelId: String,
    internal val channelName: String,
    internal val title: String,
    internal val importance: Int,
) {
    COMPLETION("hermes_completion", "Completions", "Hermes task completed", NotificationManager.IMPORTANCE_DEFAULT),
    ACTION_REQUIRED("hermes_action_required", "Action required", "Hermes needs your attention", NotificationManager.IMPORTANCE_HIGH),
    AUTOMATION_FAILURE("hermes_automation_failure", "Automation failures", "Hermes automation failed", NotificationManager.IMPORTANCE_HIGH),
    CRON_RESULT("hermes_cron_result", "Cron results", "Hermes cron run finished", NotificationManager.IMPORTANCE_DEFAULT),
}

fun createHermesNotificationChannels(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java)
    HermesNotificationKind.entries.forEach { kind ->
        manager.createNotificationChannel(
            NotificationChannel(kind.channelId, kind.channelName, kind.importance).apply {
                description = "Private ${kind.channelName.lowercase()} alerts from Hermes"
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
    }
}

fun hermesNotificationPermission(context: Context): HermesNotificationPermission {
    val manager = context.getSystemService(NotificationManager::class.java)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        return if (manager.areNotificationsEnabled()) {
            HermesNotificationPermission.GRANTED
        } else {
            HermesNotificationPermission.SETTINGS
        }
    }
    if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
        return if (manager.areNotificationsEnabled()) {
            HermesNotificationPermission.GRANTED
        } else {
            HermesNotificationPermission.SETTINGS
        }
    }
    return if (!permissionPreferences(context).getBoolean(NOTIFICATION_PERMISSION_REQUESTED, false)) {
        HermesNotificationPermission.REQUEST
    } else {
        HermesNotificationPermission.SETTINGS
    }
}

fun markHermesNotificationPermissionRequested(context: Context) {
    permissionPreferences(context).edit().putBoolean(NOTIFICATION_PERMISSION_REQUESTED, true).apply()
}

fun requestHermesNotificationPermissionIfNeeded(context: Context, request: (String) -> Unit): Boolean {
    if (hermesNotificationPermission(context) != HermesNotificationPermission.REQUEST) return false
    markHermesNotificationPermissionRequested(context)
    request(Manifest.permission.POST_NOTIFICATIONS)
    return true
}

fun postHermesNotification(
    context: Context,
    id: Int,
    kind: HermesNotificationKind,
    destination: HermesDestinationRoute,
): Boolean {
    if (hermesNotificationPermission(context) != HermesNotificationPermission.GRANTED) return false
    val publicVersion = Notification.Builder(context, kind.channelId)
        .setSmallIcon(R.drawable.ic_stat_hermes)
        .setContentTitle("Hermes")
        .setContentText("Open Hermes to view this update")
        .build()
    val notification = Notification.Builder(context, kind.channelId)
        .setSmallIcon(R.drawable.ic_stat_hermes)
        .setContentTitle(kind.title)
        .setContentText("Open Hermes for details")
        .setContentIntent(destinationPendingIntent(context, id, destination))
        .setAutoCancel(true)
        .setVisibility(Notification.VISIBILITY_PRIVATE)
        .setPublicVersion(publicVersion)
        .build()
    return runCatching {
        context.getSystemService(NotificationManager::class.java).notify(id, notification)
    }.isSuccess
}

private fun permissionPreferences(context: Context) =
    context.getSharedPreferences("hermes_permissions", Context.MODE_PRIVATE)

private const val NOTIFICATION_PERMISSION_REQUESTED = "notification_permission_requested"
