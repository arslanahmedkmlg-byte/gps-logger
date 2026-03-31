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
import android.os.CountDownTimer
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
    private lateinit var themeToggle: TextView
    private lateinit var logReceiver: BroadcastReceiver

    private var countDownTimer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply theme before setContentView
        applyTheme()
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)

            toggleBtn   = findViewById(R.id.toggleButton)
            statusText  = findViewById(R.id.statusText)
            saveStatus  = findViewById(R.id.saveStatus)
            themeToggle = findViewById(R.id.themeToggle)

            setupBroadcastReceiver()
            updateUI()
            requestAllPermissions()

            toggleBtn.setOnClickListener {
                if (isServiceRunning()) {
                    startCountdownAndStop()
                } else {
                    startGpsService()
                    finishAndRemoveTask()
                }
            }

            themeToggle.setOnClickListener {
                val prefs = getSharedPreferences(GpsLoggerService.PREFS_NAME, Context.MODE_PRIVATE)
                val isDark = prefs.getBoolean("dark_theme", true)
                prefs.edit().putBoolean("dark_theme", !isDark).apply()
                recreate()
            }

        } catch (e: Exception) {
            AlertDialog.Builder(this)
                .setTitle("Error")
                .setMessage(e.toString())
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun applyTheme() {
        val prefs = getSharedPreferences(GpsLoggerService.PREFS_NAME, Context.MODE_PRIVATE)
        val isDark = prefs.getBoolean("dark_theme", true)
        if (isDark) {
            setTheme(R.style.Theme_Dark)
        } else {
            setTheme(R.style.Theme_Light)
        }
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        countDownTimer?.cancel()
        try { unregisterReceiver(logReceiver) } catch (e: Exception) {}
    }

    private fun updateUI() {
        val prefs = getSharedPreferences(GpsLoggerService.PREFS_NAME, Context.MODE_PRIVATE)
        val isDark = prefs.getBoolean("dark_theme", true)
        themeToggle.text = if (isDark) "☀" else "☾"

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
        val prefs = getSharedPreferences(GpsLoggerService.PREFS_NAME, Context.MODE_PRIVATE)
        // If user explicitly stopped, treat as not running regardless
        if (prefs.getBoolean(GpsLoggerService.KEY_USER_STOPPED, false)) return false
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
        // Clear the user_stopped flag
        getSharedPreferences(GpsLoggerService.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(GpsLoggerService.KEY_USER_STOPPED, false).apply()

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

    private fun startCountdownAndStop() {
        // Disable button during countdown
        toggleBtn.isEnabled = false

        countDownTimer = object : CountDownTimer(3000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val secs = (millisUntilFinished / 1000) + 1
                toggleBtn.text = "$secs"
            }
            override fun onFinish() {
                // Set user_stopped flag BEFORE stopping service
                getSharedPreferences(GpsLoggerService.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putBoolean(GpsLoggerService.KEY_USER_STOPPED, true).apply()
                stopService(Intent(this@MainActivity, GpsLoggerService::class.java))
                finishAndRemoveTask()
            }
        }.start()
    }
}
