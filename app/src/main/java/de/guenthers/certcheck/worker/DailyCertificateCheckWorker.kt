package de.guenthers.certcheck.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import de.guenthers.certcheck.MainActivity
import de.guenthers.certcheck.R
import de.guenthers.certcheck.database.CertCheckDatabase
import de.guenthers.certcheck.database.CertCheckRepository
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

class DailyCertificateCheckWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val database = CertCheckDatabase.getDatabase(context)
        val repository = CertCheckRepository(database)

        val favorites = repository.getAllFavorites().first()

        if (favorites.isEmpty()) {
            return Result.success()
        }

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel(notificationManager)

        var alertCount = 0

        for (favorite in favorites) {
            try {
                val history = repository.checkAndSaveResult(favorite.id)
                val changes = repository.checkChanges(favorite.id)

                when {
                    history.daysUntilExpiry != null && history.daysUntilExpiry <= 30 -> {
                        showNotification(
                            notificationManager,
                            EXPIRY_ALERT_CHANNEL_ID,
                            favorite.id.toInt(),
                            "Certificat expire bientôt",
                            "${favorite.hostname}:${favorite.port} expire dans ${history.daysUntilExpiry} jours"
                        )
                        alertCount++
                    }

                    changes != null -> {
                        showNotification(
                            notificationManager,
                            CHANGE_ALERT_CHANNEL_ID,
                            favorite.id.toInt() + 1000,
                            "Changement détecté",
                            "${favorite.hostname}: ${changes.changes.joinToString(", ")}"
                        )
                        alertCount++
                    }

                    history.error != null -> {
                        showNotification(
                            notificationManager,
                            ERROR_ALERT_CHANNEL_ID,
                            favorite.id.toInt() + 2000,
                            "Erreur de connexion",
                            "${favorite.hostname}: ${history.error}"
                        )
                        alertCount++
                    }
                }
            } catch (e: Exception) {
                showNotification(
                    notificationManager,
                    ERROR_ALERT_CHANNEL_ID,
                    favorite.id.toInt() + 2000,
                    "Erreur",
                    "Échec de vérification pour ${favorite.hostname}: ${e.message}"
                )
            }
        }

        return Result.success()
    }

    private fun createNotificationChannel(notificationManager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            listOf(
                NotificationChannel(
                    EXPIRY_ALERT_CHANNEL_ID,
                    "Alertes d'expiration",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = "Alertes quand un certificat expire bientôt" },
                NotificationChannel(
                    CHANGE_ALERT_CHANNEL_ID,
                    "Changements détectés",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = "Alertes quand un certificat change" },
                NotificationChannel(
                    ERROR_ALERT_CHANNEL_ID,
                    "Erreurs de connexion",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { description = "Alertes quand la connexion échoue" }
            ).forEach { notificationManager.createNotificationChannel(it) }
        }
    }

    private fun showNotification(
        notificationManager: NotificationManager,
        channelId: String,
        id: Int,
        title: String,
        message: String
    ) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(id, notification)
    }

    companion object {
        const val WORK_NAME = "daily_certificate_check"
        const val EXPIRY_ALERT_CHANNEL_ID = "expiry_alerts"
        const val CHANGE_ALERT_CHANNEL_ID = "change_alerts"
        const val ERROR_ALERT_CHANNEL_ID = "error_alerts"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<DailyCertificateCheckWorker>(
                1, TimeUnit.DAYS
            )
                .setConstraints(constraints)
                .setInitialDelay(1, TimeUnit.HOURS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
