package com.ars.gpslogger

import android.Manifest
import android.app.ActivityManager
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
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private val PERM_REQUEST = 1

    private lateinit var toggleBtn: Button
    private lateinit var statusText: TextView
    private lateinit var saveStatus: TextView
    private lateinit var logReceiver: BroadcastReceiver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)

            toggleBtn  = findViewById(R.id.toggleButton)
            statusText = findViewById(R.id.statusText)
            saveStatus = findViewById(R.id.saveStatus)

            setupBroadcastReceiver()
            updateUI()
            requestAllPermissions()

            toggleBtn.setOnClickListener {
                if (isServiceRunning()) {
                    stopGpsService()
                } else {
                    startGpsService()
                    finishAndRemoveTask()
                }
            }

        } catch (e: Exception) {
            AlertDialog.Builder(this)
                .setTitle("Error")
                .setMessage(e.toString())
                .setPositiveButton("OK", null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(logReceiver) } catch (e: Exception) {}
    }

    private fun updateUI() {
        if (isServiceRunning()) {
            statusText.text = "ACTIVE"
            toggleBtn.text = "STOP"
            toggleBtn.setBackgroundResource(R.drawable.btn_neon_red)
        } else {
            statusText.text = "INACTIVE"
            toggleBtn.text = "START"
            toggleBtn.setBackgroundResource(R.drawable.btn_neon_green)
        }
    }

    private fun setupBroadcastReceiver() {
        logReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val ts = intent?.getLongExtra("timestamp", 0L) ?: 0L
                if (ts > 0) {
                    saveStatus.text = "last ping  ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ts))}"
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, IntentFilter("GPS_LOG_SAVED"), RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(logReceiver, IntentFilter("GPS_LOG_SAVED"))
        }
    }

    private fun isServiceRunning(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        return am.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == GpsLoggerService::class.java.name }
    }

    private fun requestAllPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            perms.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERM_REQUEST)
        }
    }

    private fun startGpsService() {
        val intent = Intent(this, GpsLoggerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        val logIntent = Intent(this, GpsLoggerService::class.java)
        logIntent.action = GpsLoggerService.ACTION_LOG_NOW
        startService(logIntent)
    }

    private fun stopGpsService() {
        stopService(Intent(this, GpsLoggerService::class.java))
        updateUI()
    }
}
