package com.ars.gpslogger

import android.Manifest
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private var isRunning = false

    private lateinit var toggleBtn: Button
    private lateinit var statusText: TextView
    private lateinit var saveStatus: TextView
    private lateinit var logReceiver: BroadcastReceiver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)

            toggleBtn = findViewById(R.id.toggleButton)
            statusText = findViewById(R.id.statusText)
            saveStatus = findViewById(R.id.saveStatus)

            setupBroadcastReceiver()

            toggleBtn.setOnClickListener {
                if (isRunning) {
                    stopGpsService()
                } else {
                    startGpsService()
                }
            }

            if (!hasPermissions()) {
                ActivityCompat.requestPermissions(this, PERMISSIONS, 1)
            }
        } catch (e: Exception) {
            AlertDialog.Builder(this)
                .setTitle("Crash Error")
                .setMessage(e.toString())
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun setupBroadcastReceiver() {
        logReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val timestamp = intent?.getLongExtra("timestamp", 0L) ?: 0L
                saveStatus.text = "Last saved: ${java.util.Date(timestamp)}"
            }
        }
        registerReceiver(logReceiver, IntentFilter("GPS_LOG_SAVED"))
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(logReceiver)
    }

    private fun hasPermissions(): Boolean =
        PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    private fun startGpsService() {
        val intent = Intent(this, GpsLoggerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        isRunning = true
        statusText.text = "GPS Logger is ON"
        toggleBtn.text = "Stop Logging"
    }

    private fun stopGpsService() {
        stopService(Intent(this, GpsLoggerService::class.java))
        isRunning = false
        statusText.text = "GPS Logger is OFF"
        toggleBtn.text = "Start Logging"
    }
}
