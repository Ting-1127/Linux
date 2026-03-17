package com.github.linux.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.github.linux.LinuxContainer
import com.github.linux.model.ContainerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.net.NetworkInterface
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class LinuxService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var linux: LinuxContainer
    private lateinit var logFile: File
    private var sshdProcess: Process? = null

    @Volatile
    private var isStarting = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        linux = LinuxContainer(application)
        logFile = File(getExternalFilesDir(""), "log.txt")
        logFile.parentFile?.mkdirs()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        if (sshdProcess?.isAlive == true || isStarting) return START_STICKY
        isStarting = true
        serviceScope.launch {
            runCatching { startLinux() }.onFailure { appendLog("ERROR: ${it.message}") }
            isStarting = false
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        sshdProcess?.destroy()
        serviceScope.cancel()
    }

    private fun startLinux() {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        logFile.writeText("=== Linux Debug Log [$timestamp] ===\n")
        appendLog("[log] Output saved to: ${logFile.absolutePath}")
        val log = LinuxContainer.ProgressListener { appendLog(it) }

        val containerId = "alpine"
        val imageUrl = "https://mirrors.aliyun.com/alpine/v3.21/releases/aarch64/alpine-minirootfs-3.21.3-aarch64.tar.gz"
        val sshPort = 2224
        val sshPassword = "root"

        // 1. 安装 rootfs
        if (!linux.isInstalled(containerId)) {
            linux.install(containerId, imageUrl, log)
        } else {
            appendLog("Rootfs ready at: ${linux.rootfsDir(containerId).absolutePath}")
        }

        // 2. 同步脚本
        linux.syncScriptsToContainer(containerId, listener = log)

        // 3. 配置 DNS
        linux.setupDns(containerId, log)

        // 4. 安装必要软件
        val installed = linux.ensureBinariesFromAssets(
            containerId = containerId,
            binaries = listOf("/usr/sbin/sshd", "/usr/bin/ssh-keygen"),
            scriptAsset = "init_packages.sh",
            listener = log,
        )
        if (!installed) return

        // 5. 启动 SSH
        appendLog("Configuring and starting sshd...")
        val process = linux.startScriptFromAssets(
            containerId = containerId,
            scriptAsset = "setup_sshd.sh",
            env = mapOf(
                "SSH_PORT" to sshPort.toString(),
                "SSH_PASSWORD" to sshPassword,
                "SSH_USER" to "root",
            ),
        )
        sshdProcess = process

        Thread.sleep(1000)
        if (process.isAlive) {
            val ip = getLocalIpAddress()
            appendLog("=== SSH Server Started ===")
            appendLog("  ssh root@$ip -p $sshPort")
            appendLog("  Password: $sshPassword")
            appendLog("==========================")
        }

        process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { appendLog(it) }
        }
        appendLog("[sshd exited with code ${process.waitFor()}]")
        sshdProcess = null
    }

    private fun getLocalIpAddress(): String {
        return try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .flatMap { it.inetAddresses.asSequence() }
                .filterNot { it.isLoopbackAddress }
                .filter { it.address.size == 4 }
                .map { it.hostAddress }
                .firstOrNull() ?: "127.0.0.1"
        } catch (_: Exception) {
            "127.0.0.1"
        }
    }

    private fun appendLog(text: String) {
        try {
            logFile.appendText("$text\n")
        } catch (_: Exception) {}
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Linux 正在运行")
            .setContentText("前台服务已启动，点击返回应用")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Linux 前台服务",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "保持 Linux 在后台持续运行" }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "linux_service"
        private const val NOTIFICATION_ID = 10001
    }
}
