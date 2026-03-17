package com.github.linux

import android.app.Application
import android.content.res.AssetManager
import com.github.linux.engine.PRootEngine
import com.github.linux.model.ContainerConfig
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Linux 容器生命周期管理器：负责安装、配置和脚本执行。
 */
class LinuxContainer(private val app: Application) {

    private val assets: AssetManager = app.assets
    private val containersDir: File = File(app.dataDir, DIR_CONTAINERS)
    val engine: PRootEngine = PRootEngine(app)

    /** 进度回调接口 */
    fun interface ProgressListener {
        fun onProgress(message: String)
    }

    /** 检查容器是否已安装 */
    fun isInstalled(containerId: String, checkFile: String = "bin/busybox"): Boolean {
        val rootfsDir = File(containersDir, containerId)
        return rootfsDir.exists() && File(rootfsDir, checkFile).exists()
    }

    /** 获取指定容器的 rootfs 目录 */
    fun rootfsDir(containerId: String): File = File(containersDir, containerId)

    /**
     * 下载并解压 rootfs 镜像。
     */
    fun install(
        containerId: String,
        imageUrl: String,
        listener: ProgressListener? = null,
    ) {
        val rootfsDir = rootfsDir(containerId)
        if (File(rootfsDir, "bin/busybox").exists()) {
            listener?.onProgress("Rootfs already extracted.")
            return
        }
        rootfsDir.mkdirs()

        val fileName = imageUrl.substringAfterLast("/")
        val tmpFile = File(containersDir, fileName)

        if (!tmpFile.exists()) {
            listener?.onProgress("Downloading $fileName ...")
            val downloader = MultiThreadDownloader(imageUrl, tmpFile, threadCount = 4)
            var lastReportTime = 0L
            downloader.download { downloaded, total, speed ->
                val now = System.currentTimeMillis()
                if (now - lastReportTime >= 500) {
                    lastReportTime = now
                    val pct = if (total > 0) downloaded * 100 / total else 0
                    val mb = downloaded / 1024 / 1024
                    val totalMb = total / 1024 / 1024
                    val speedKb = speed / 1024
                    listener?.onProgress("[download] $mb/$totalMb MB ($pct%) - $speedKb KB/s")
                }
            }
            listener?.onProgress("Download complete: ${tmpFile.absolutePath}")
        } else {
            listener?.onProgress("Using cached file: ${tmpFile.absolutePath}")
        }

        listener?.onProgress("Extracting $fileName ...")
        extractTar(tmpFile, rootfsDir, listener)

        // 确保 /bin/sh 存在
        val shFile = File(rootfsDir, "bin/sh")
        if (!shFile.exists()) {
            val busybox = File(rootfsDir, "bin/busybox")
            if (busybox.exists()) {
                try {
                    busybox.copyTo(shFile)
                    shFile.setExecutable(true, false)
                    listener?.onProgress("[fix] Created /bin/sh from busybox")
                } catch (e: Exception) {
                    listener?.onProgress("[warn] Failed to create /bin/sh: ${e.message}")
                }
            }
        }

        listener?.onProgress("Extraction complete.")
        tmpFile.delete()
    }

    /** 配置 DNS */
    fun setupDns(containerId: String, listener: ProgressListener? = null) {
        val rootfsDir = rootfsDir(containerId)
        val nameservers = listOf("8.8.8.8", "8.8.4.4")
        val resolvConf = File(rootfsDir, "etc/resolv.conf")
        resolvConf.parentFile?.mkdirs()
        if (resolvConf.exists() || Files.isSymbolicLink(resolvConf.toPath())) {
            resolvConf.delete()
        }
        resolvConf.writeText(nameservers.joinToString("\n") { "nameserver $it" } + "\n")
        listener?.onProgress("[dns] resolv.conf set to: ${nameservers.joinToString(", ")}")
    }

    /** 在容器中执行脚本 */
    fun runScript(
        containerId: String,
        script: String,
        env: Map<String, String> = emptyMap(),
        config: ContainerConfig = ContainerConfig(),
        listener: ProgressListener? = null,
    ): Int {
        val normalized = script.replace("\r\n", "\n").replace("\r", "\n")
        val proc = engine.startProcess(
            containerId = containerId,
            config = config.copy(
                cmd = listOf("/bin/sh", "-c", normalized),
                env = env,
            ),
        ).getOrThrow()
        proc.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { listener?.onProgress(it) }
        }
        return proc.waitFor()
    }

    /** 在容器中执行 assets 中的脚本 */
    fun runScriptFromAssets(
        containerId: String,
        scriptAsset: String,
        env: Map<String, String> = emptyMap(),
        config: ContainerConfig = ContainerConfig(),
        listener: ProgressListener? = null,
    ): Int {
        val script = assets.open(scriptAsset).bufferedReader().readText()
        return runScript(containerId, script, env, config, listener)
    }

    /** 启动长期运行的脚本 */
    fun startScript(
        containerId: String,
        script: String,
        env: Map<String, String> = emptyMap(),
        config: ContainerConfig = ContainerConfig(),
    ): Process {
        val normalized = script.replace("\r\n", "\n").replace("\r", "\n")
        return engine.startProcess(
            containerId = containerId,
            config = config.copy(
                cmd = listOf("/bin/sh", "-c", normalized),
                env = env,
            ),
        ).getOrThrow()
    }

    /** 启动 assets 中的脚本 */
    fun startScriptFromAssets(
        containerId: String,
        scriptAsset: String,
        env: Map<String, String> = emptyMap(),
        config: ContainerConfig = ContainerConfig(),
    ): Process {
        val script = assets.open(scriptAsset).bufferedReader().readText()
        return startScript(containerId, script, env, config)
    }

    /** 检测并安装必要的二进制文件 */
    fun ensureBinaries(
        containerId: String,
        binaries: List<String>,
        installScript: String,
        env: Map<String, String> = emptyMap(),
        config: ContainerConfig = ContainerConfig(),
        listener: ProgressListener? = null,
    ): Boolean {
        val rootfsDir = rootfsDir(containerId)
        val missing = binaries.filter { !File(rootfsDir, it.removePrefix("/")).exists() }
        if (missing.isEmpty()) {
            listener?.onProgress("Required binaries already installed.")
            return true
        }
        listener?.onProgress("Missing binaries: ${missing.joinToString()} — installing...")
        val exitCode = runScript(containerId, installScript, env, config, listener)
        if (exitCode != 0) {
            listener?.onProgress("[install failed with exit code $exitCode]")
            return false
        }
        val stillMissing = binaries.filter { !File(rootfsDir, it.removePrefix("/")).exists() }
        if (stillMissing.isNotEmpty()) {
            listener?.onProgress("[ERROR] Binaries still missing: ${stillMissing.joinToString()}")
            return false
        }
        return true
    }

    /** 检测并安装 assets 中的脚本 */
    fun ensureBinariesFromAssets(
        containerId: String,
        binaries: List<String>,
        scriptAsset: String,
        env: Map<String, String> = emptyMap(),
        config: ContainerConfig = ContainerConfig(),
        listener: ProgressListener? = null,
    ): Boolean {
        val installScript = assets.open(scriptAsset).bufferedReader().readText()
        return ensureBinaries(containerId, binaries, installScript, env, config, listener)
    }

    /** 同步 assets 脚本到容器 */
    fun syncScriptsToContainer(
        containerId: String,
        scriptsAssetDir: String = "scripts",
        targetDir: String = "/root/scripts",
        listener: ProgressListener? = null,
    ) {
        val rootfsDir = rootfsDir(containerId)
        val targetRoot = File(rootfsDir, targetDir.removePrefix("/"))
        targetRoot.mkdirs()
        val scriptFiles = mutableListOf<String>()
        collectAssetFilesRecursively(scriptsAssetDir, scriptFiles)
        if (scriptFiles.isEmpty()) {
            listener?.onProgress("No scripts found in assets/$scriptsAssetDir")
            return
        }
        scriptFiles.forEach { assetPath ->
            val relativePath = assetPath.removePrefix("$scriptsAssetDir/")
            val outFile = File(targetRoot, relativePath)
            outFile.parentFile?.mkdirs()
            if (relativePath.endsWith(".sh")) {
                val text = assets.open(assetPath).bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                val normalized = text.replace("\r\n", "\n").replace("\r", "\n")
                outFile.writeText(normalized, StandardCharsets.UTF_8)
            } else {
                assets.open(assetPath).use { input ->
                    FileOutputStream(outFile).use { output -> input.copyTo(output) }
                }
            }
            outFile.setReadable(true, false)
            outFile.setWritable(true, true)
            outFile.setExecutable(true, false)
            listener?.onProgress("[scripts] Synced: $targetDir/$relativePath")
        }
    }

    private fun collectAssetFilesRecursively(dir: String, files: MutableList<String>) {
        val entries = assets.list(dir).orEmpty()
        if (entries.isEmpty()) {
            runCatching { assets.open(dir).close() }.onSuccess { files.add(dir) }
            return
        }
        entries.forEach { entry -> collectAssetFilesRecursively("$dir/$entry", files) }
    }

    private fun extractTar(srcFile: File, targetDir: File, listener: ProgressListener?) {
        val rootCanonical = targetDir.canonicalFile
        srcFile.inputStream().use { raw ->
            val src = if (srcFile.name.endsWith(".gz"))
                GzipCompressorInputStream(BufferedInputStream(raw))
            else
                BufferedInputStream(raw)
            TarArchiveInputStream(src).use { tar ->
                var count = 0
                var entry = tar.nextTarEntry
                while (entry != null) {
                    val out = if (entry.isSymbolicLink)
                        File(rootCanonical, entry.name)
                    else
                        File(rootCanonical, entry.name).canonicalFile
                    require(
                        out.toPath() == rootCanonical.toPath() ||
                        out.toPath().startsWith(rootCanonical.toPath())
                    ) { "Unsafe tar path: ${entry.name}" }
                    when {
                        entry.isDirectory -> {
                            out.mkdirs()
                            applyMode(out, entry.mode)
                        }
                        entry.isSymbolicLink -> {
                            out.parentFile?.mkdirs()
                            try {
                                Files.deleteIfExists(out.toPath())
                                Files.createSymbolicLink(out.toPath(), Path.of(entry.linkName))
                            } catch (e: Exception) {
                                listener?.onProgress("[warn] Skipped symlink: ${entry.name}")
                            }
                        }
                        else -> {
                            out.parentFile?.mkdirs()
                            FileOutputStream(out).use { tar.copyTo(it) }
                            applyMode(out, entry.mode)
                        }
                    }
                    count++
                    if (count % 500 == 0) listener?.onProgress("Extracted $count entries...")
                    entry = tar.nextTarEntry
                }
                listener?.onProgress("Extracted $count entries total.")
            }
        }
    }

    private fun applyMode(file: File, mode: Int) {
        file.setReadable(mode and 0x124 != 0, false)
        file.setWritable(mode and 0x92 != 0, false)
        file.setExecutable(mode and 0x49 != 0, false)
    }

    companion object {
        private const val DIR_CONTAINERS = "containers"
    }
}
