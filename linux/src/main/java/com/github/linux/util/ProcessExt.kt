package com.github.linux.util

import java.io.File

/**
 * 进程构建工具函数
 */
fun Process(
    command: List<String>,
    workingDir: File? = null,
    environment: Map<String, String> = emptyMap(),
    redirectErrorStream: Boolean = true,
): Process {
    val processBuilder = ProcessBuilder(command).apply {
        workingDir?.let { directory(it) }
        environment().putAll(environment)
        redirectErrorStream(redirectErrorStream)
    }
    return processBuilder.start()
}
