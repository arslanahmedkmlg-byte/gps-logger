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

        const val MQTT_HOST  = "tcp://144.24.113.211:1883"
        const val MQTT_USER  = "ars"
        const val MQTT_PASS  = "@r$"
        const val MQTT_TOPIC = "gps/phone1"

        const val DEVICE_ID  = "phone1"

        const val GPS_INTERVAL_MS    = 5 * 60 * 1000L   // 5 min
        const val UPLOAD_INTERVAL_MS = 6 * 60 * 1000L   // 6 min

        const val CHANNEL_ID = "gps_service"
        const val NOTIF_ID   = 1
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()

        handler.removeCallbacks(gpsRunnable)
        handler.removeCallbacks(uploadRunnable)

        // Auto restart
        startService(Intent(applicationContext, GpsLoggerService::class.java))
    }

    /* ------------------------- GPS LOCATION CAPTURE --------------------------- */

    private fun captureLocation() {
        try {
            if (ContextCompat.checkSelfPermission(
