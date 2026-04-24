package com.example.resqnow.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.resqnow.R
import com.example.resqnow.ui.home.MainActivity
import com.example.resqnow.util.PreferencesManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class ResqFirebaseMessagingService : FirebaseMessagingService() {

    private val prefs by lazy { PreferencesManager(applicationContext) }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        prefs.saveFcmToken(token)
        Log.d(TAG, "New FCM token: $token")
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val title = remoteMessage.data["title"]
            ?: remoteMessage.notification?.title
            ?: "Highway SOS alert"
        val body = remoteMessage.data["body"]
            ?: remoteMessage.data["message"]
            ?: remoteMessage.notification?.body
            ?: "You have a new update."
        sendNotification(title, body)
    }

    private fun sendNotification(title: String, message: String) {
        val channelId = "resqnow_alerts"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "ResQnow alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Real-time SOS and dispatch messages"
                enableVibration(true)
            }
            manager.createNotificationChannel(channel)
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, flags)

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_sos_tile)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        manager.notify((System.currentTimeMillis() % 100000).toInt(), notification)
    }

    companion object {
        private const val TAG = "ResqFCM"
    }
}
