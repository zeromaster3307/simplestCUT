package app.simplestcut.export

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import app.simplestcut.R

/**
 * Schutz für laufende Exporte (SPEC "Hintergrund-Export", Stufe 1): Foreground-Service vom Typ mediaProcessing mit
 * Benachrichtigung (Fortschritt, Abbrechen), dazu ein Wakelock, damit das Neukodieren bei ausgeschaltetem Bildschirm
 * weiterläuft. Der Export selbst läuft weiter im Editor; der Dienst sorgt nur dafür, dass Android die App
 * währenddessen nicht beendet.
 */
class ExportService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> onCancel?.invoke()
            else -> {
                startForeground(NOTIFICATION_ID, notification(this, title, null), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
                if (wakeLock == null) {
                    wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "simplestCUT:export")
                        .apply { acquire(6 * 60 * 60 * 1000L) }
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15+: mediaProcessing darf höchstens 6 Stunden am Tag laufen. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        onCancel?.invoke()
        stopSelf()
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "export"
        private const val NOTIFICATION_ID = 1
        private const val DONE_NOTIFICATION_ID = 2
        private const val ACTION_CANCEL = "app.simplestcut.CANCEL_EXPORT"

        private var title = ""
        private var lastPercent = -1
        /** Wird beim Tippen auf "Abbrechen" in der Benachrichtigung aufgerufen. */
        private var onCancel: (() -> Unit)? = null

        /** Beim Start eines Exports (App muss sichtbar sein). */
        fun start(context: Context, title: String, onCancel: () -> Unit) {
            this.title = title
            this.onCancel = onCancel
            lastPercent = -1
            createChannel(context)
            context.getSystemService(NotificationManager::class.java).cancel(DONE_NOTIFICATION_ID)
            context.startForegroundService(Intent(context, ExportService::class.java))
        }

        /** Fortschritt 0..1; die Benachrichtigung wird nur bei jedem ganzen Prozent erneuert. */
        fun progress(context: Context, value: Float) {
            val percent = (value * 100).toInt().coerceIn(0, 100)
            if (percent == lastPercent || onCancel == null) return
            lastPercent = percent
            context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(context, title, percent))
        }

        /** Export beendet; [doneText] (falls gesetzt) bleibt als Benachrichtigung stehen, z. B. wenn die App im Hintergrund ist. */
        fun stop(context: Context, doneText: String? = null) {
            onCancel = null
            context.stopService(Intent(context, ExportService::class.java))
            if (doneText != null) {
                val n = Notification.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification_export)
                    .setContentTitle(doneText)
                    .setContentIntent(openApp(context))
                    .setAutoCancel(true)
                    .build()
                context.getSystemService(NotificationManager::class.java).notify(DONE_NOTIFICATION_ID, n)
            }
        }

        private fun createChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        private fun openApp(context: Context): PendingIntent {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            return PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE)
        }

        private fun notification(context: Context, title: String, percent: Int?): Notification {
            val cancel = PendingIntent.getService(
                context, 0,
                Intent(context, ExportService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE,
            )
            return Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_export)
                .setContentTitle(title)
                .setProgress(100, percent ?: 0, percent == null)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .setContentIntent(openApp(context))
                .addAction(Notification.Action.Builder(null, context.getString(android.R.string.cancel), cancel).build())
                .build()
        }
    }
}
