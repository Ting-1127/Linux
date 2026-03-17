package com.github.linux.model

/**
 * 容器运行配置
 */
data class ContainerConfig(
    /** 容器默认执行的命令 */
    val cmd: List<String> = listOf("/bin/sh"),
    /** 入口点命令（优先于 cmd） */
    val entrypoint: List<String>? = null,
    /** 传递给容器的环境变量 */
    val env: Map<String, String> = emptyMap(),
    /** 容器内的工作目录 */
    val workingDir: String = "/",
    /** 运行用户 */
    val user: String = "root",
    /** 容器主机名 */
    val hostname: String = "localhost",
    /** 宿主机目录绑定列表 */
    val binds: List<VolumeBinding> = emptyList(),
)

/**
 * 宿主机与容器之间的目录绑定
 */
data class VolumeBinding(
    /** 宿主机路径 */
    val hostPath: String,
    /** 容器内路径 */
    val containerPath: String,
)
