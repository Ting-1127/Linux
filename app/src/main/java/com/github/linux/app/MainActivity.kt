package com.github.linux.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

class MainActivity : Activity() {
    private lateinit var tvConsole: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var logFile: File
    private val uiHandler = Handler(Looper.getMainLooper())
    private var lastLogText: String = ""

    private val logRefreshRunnable = object : Runnable {
        override fun run() {
            refreshLog()
            uiHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startLinuxService()
        setContentView(R.layout.activity_main)

        tvConsole = findViewById(R.id.tvConsole)
        scrollView = findViewById(R.id.scrollView)
        logFile = File(getExternalFilesDir(""), "log.txt")
        logFile.parentFile?.mkdirs()
        refreshLog()
    }

    private fun startLinuxService() {
        val serviceIntent = Intent(this, LinuxService::class.java)
        startForegroundService(serviceIntent)
    }

    override fun onResume() {
        super.onResume()
        uiHandler.post(logRefreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(logRefreshRunnable)
    }

    private fun refreshLog() {
        val text = if (logFile.exists()) logFile.readText() else ""
        if (text != lastLogText) {
            lastLogText = text
            tvConsole.text = text
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }
}
