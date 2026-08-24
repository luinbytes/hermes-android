package com.nousresearch.hermes.platform

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import com.nousresearch.hermes.ui.navigation.HermesDestinationRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.VANILLA_ICE_CREAM])
class HermesNotificationsTest {
    @Test
    @Config(sdk = [Build.VERSION_CODES.BAKLAVA])
    fun freshInstallRequestsNotificationPermissionExactlyOnce() {
        val context = RuntimeEnvironment.getApplication<android.app.Application>()
        context.getSharedPreferences("hermes_permissions", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        val requested = mutableListOf<String>()

        assertTrue(requestHermesNotificationPermissionIfNeeded(context, requested::add))
        assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), requested)
        assertFalse(requestHermesNotificationPermissionIfNeeded(context, requested::add))
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.P])
    fun preAndroid13PostsWithoutRuntimeNotificationPermission() {
        val context = RuntimeEnvironment.getApplication<android.app.Application>()
        createHermesNotificationChannels(context)

        assertEquals(HermesNotificationPermission.GRANTED, hermesNotificationPermission(context))
        assertFalse(requestHermesNotificationPermissionIfNeeded(context) {})
        assertTrue(
            postHermesNotification(
                context,
                id = 40,
                kind = HermesNotificationKind.COMPLETION,
                destination = HermesDestinationRoute.Chats("backend", "profile", "session"),
            ),
        )
    }

    @Test
    fun channelsAndRenderedContentStayPrivate() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(NotificationManager::class.java)
        createHermesNotificationChannels(context)

        HermesNotificationKind.entries.forEach { kind ->
            assertEquals(Notification.VISIBILITY_PRIVATE, manager.getNotificationChannel(kind.channelId).lockscreenVisibility)
        }

        assertEquals(HermesNotificationPermission.REQUEST, hermesNotificationPermission(context))
        assertFalse(
            postHermesNotification(
                context,
                id = 41,
                kind = HermesNotificationKind.COMPLETION,
                destination = HermesDestinationRoute.Chats("backend", "profile", "session"),
            ),
        )
        markHermesNotificationPermissionRequested(context)
        assertEquals(HermesNotificationPermission.SETTINGS, hermesNotificationPermission(context))
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(HermesNotificationPermission.GRANTED, hermesNotificationPermission(context))
        val destination = HermesDestinationRoute.Chats("backend", "profile", "session")
        assertTrue(
            postHermesNotification(
                context,
                id = 42,
                kind = HermesNotificationKind.ACTION_REQUIRED,
                destination = destination,
            ),
        )
        val notification = manager.activeNotifications.single { it.id == 42 }.notification
        assertEquals("Hermes needs your attention", notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Open Hermes for details", notification.extras.getString(Notification.EXTRA_TEXT))
        assertEquals("Hermes", notification.publicVersion.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertTrue(notification.actions.isNullOrEmpty())
        val request = parseHermesEntryRequest(shadowOf(notification.contentIntent).savedIntent, context.packageName)
        assertEquals(destination, (request as HermesEntryRequest.OpenDestination).route)
    }
}
