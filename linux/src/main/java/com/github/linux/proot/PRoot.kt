package com.github.linux.proot

/**
 * PRoot JNI 接口
 */
object PRoot {

    init {
        System.loadLibrary("proot_ext")
    }

    /** 获取 PRoot 版本号 */
    @JvmStatic
    external fun getVersion(): String
}
