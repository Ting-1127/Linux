package com.github.linux.engine

import android.app.Application
import com.github.linux.model.ContainerConfig
import com.github.linux.model.VolumeBinding
import com.github.linux.proot.PRoot
import com.github.linux.util.binary
import com.github.linux.util.containersDir
import com.github.linux.util.environment
import com.github.linux.util.redirect
import java.io.File

/**
 * PRoot 引擎：负责构建命令行、环境变量，并在容器中启动进程。
 */
class PRootEngine(private val app: Application) {

    val version: String = PRoot.getVersion()

    /**
     * 在指定容器中启动进程。
     */
    fun startProcess(
        containerId: String,
        command: List<String>? = null,
        config: ContainerConfig = ContainerConfig(),
    ): Result<Process> = runCatching {
        val rootfsDir = File(app.containersDir, containerId)
        if (!rootfsDir.exists()) {
            throw IllegalStateException("Container rootfs not found")
        }
        Process(
            command = buildCommand(config, rootfsDir, command),
            workingDir = rootfsDir,
            environment = buildEnvironment(config),
        )
    }

    private fun buildCommand(
        container: ContainerConfig,
        rootfsDir: File,
        command: List<String>?,
    ): List<String> {
        val cmd = mutableListOf<String>()
        cmd.add(app.binary.absolutePath)
        cmd.add("-0")
        cmd.add("--link2symlink")
        cmd.add("-r")
        cmd.add(rootfsDir.absolutePath)
        cmd.add("-w")
        cmd.add(container.workingDir)

        val mapping = app.redirect
        container.binds.forEach { bind ->
            cmd.add("-b")
            val hostPath = mapping.getOrElse(bind.hostPath) { bind.hostPath }
            cmd.add("$hostPath:${bind.containerPath}")
        }

        addEssentialBinds(cmd, rootfsDir)
        cmd.addAll(command ?: buildExecCommand(container))
        return cmd
    }

    private fun buildEnvironment(container: ContainerConfig): Map<String, String> {
        val env = HashMap<String, String>(app.environment)
        env["HOME"] = "/root"
        env["USER"] = container.user.ifEmpty { "root" }
        env["HOSTNAME"] = container.hostname
        env.putAll(container.env)
        return env
    }

    companion object {
        private fun buildExecCommand(config: ContainerConfig): List<String> {
            val cmd = mutableListOf<String>()
            cmd.addAll(config.entrypoint ?: emptyList())
            cmd.addAll(config.cmd)
            return cmd.ifEmpty { listOf("/bin/sh") }
        }

        private fun addEssentialBinds(cmd: MutableList<String>, rootfsDir: File) {
            // 绑定基础设备节点
            listOf("/dev/null", "/dev/zero", "/dev/random", "/dev/urandom", "/dev/ptmx", "/dev/tty").forEach { dev ->
                if (File(dev).exists()) {
                    cmd.add("-b")
                    cmd.add(dev)
                }
            }

            // 绑定 PTY 伪终端
            if (File("/dev/pts").exists()) {
                cmd.add("-b")
                cmd.add("/dev/pts")
            }

            cmd.add("-b")
            cmd.add("/proc")

            if (File("/sys").exists()) {
                cmd.add("-b")
                cmd.add("/sys")
            }

            // 绑定 Android 系统分区
            listOf("/system", "/vendor").forEach { path ->
                if (File(path).exists()) {
                    cmd.add("-b")
                    cmd.add("$path:$path")
                }
            }

            // 确保 DNS 和 hosts 文件存在
            val resolvConf = File(rootfsDir, "etc/resolv.conf")
            if (!resolvConf.exists()) {
                resolvConf.parentFile?.mkdirs()
                resolvConf.writeText("nameserver 8.8.8.8\nnameserver 8.8.4.4\n")
            }

            val hostsFile = File(rootfsDir, "etc/hosts")
            if (!hostsFile.exists()) {
                hostsFile.parentFile?.mkdirs()
                hostsFile.writeText("127.0.0.1 localhost\n::1 localhost\n")
            }
        }
    }
}
