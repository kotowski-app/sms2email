package app.kotowski.sms2email.system

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.kotowski.sms2email.R
import app.kotowski.sms2email.util.AndroidVersion
import app.kotowski.sms2email.util.LanguageHelper
import app.kotowski.sms2email.util.Logger

private const val CHANNEL_ID = "sms_forwarding_channel"
private const val NOTIFICATION_ID = 1

object ForwardingNotification {
    fun show(
        context: Context,
        openIntent: Intent,
    ) {
        // Below 33 the manifest permission is granted at install, so the gate is 33+ only;
        // with the permission denied notify() is a silent no-op, so the skip is logged.
        if (
            AndroidVersion.is13Plus() &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Logger.w("ForwardingNotification", "POST_NOTIFICATIONS denied, the forwarding notification is not shown")
            return
        }
        try {
            val localeCtx = LanguageHelper.applyLocale(context)
            createChannel(localeCtx)
            val notification = buildNotification(localeCtx, context, openIntent)
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Logger.w("ForwardingNotification", "Failed to show notification: ${e.javaClass.simpleName}", e)
        }
    }

    fun dismiss(context: Context) {
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(NOTIFICATION_ID)
        } catch (e: Exception) {
            Logger.w("ForwardingNotification", "Failed to dismiss notification: ${e.javaClass.simpleName}", e)
        }
    }

    private fun createChannel(context: Context) {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.sms_forwarding),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(
        localeCtx: Context,
        baseCtx: Context,
        openIntent: Intent,
    ) = NotificationCompat
        .Builder(localeCtx, CHANNEL_ID)
        .apply {
            val intent =
                openIntent.apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
            setContentTitle(localeCtx.getString(R.string.app_name))
            setContentText(localeCtx.getString(R.string.dashboard_status_listening))
            setSmallIcon(R.drawable.ic_notification)
            setContentIntent(
                PendingIntent.getActivity(
                    baseCtx,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            setOngoing(true)
            setPriority(NotificationCompat.PRIORITY_LOW)
        }.build()
}
