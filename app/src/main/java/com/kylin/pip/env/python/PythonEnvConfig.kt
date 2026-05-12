package com.kylin.pip.env.python

import android.content.Context
import android.os.Build
import java.io.File

/**
 * 所有路径常量和运行时环境变量配置。
 * 基于 p4a 实际产物目录结构定义。
 */
class PythonEnvConfig(context: Context) {

    // 当前设备 ABI（取第一个，即最优先的）
    val abi: String = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"

    // assets 中实际存在的 zip 对应的 ABI（x86 模拟器回退到 x86_64）
    val assetAbi: String = when (abi) {
        "x86" -> "x86_64"
        else  -> abi
    }

    // assets 中对应的 zip 文件名（p4a 构建产物为 3.11）
    val assetZipName: String = "python-3.11-$assetAbi.zip"

    // 版本标识，与 zip 文件对应；更新 zip 时同步修改此值以触发重新解压
    val buildVersion: String = "3.11-p4a-build1"

    // ── 根目录 ──────────────────────────────────────────────────────────────
    val pythonRoot: File = File(context.filesDir, "python")

    // ── Python bundle（stdlib、扩展模块、site-packages）────────────────────
    val pythonBundle: File = File(pythonRoot, "_python_bundle")
    val stdlibZip: File    = File(pythonBundle, "stdlib.zip")
    val modules: File      = File(pythonBundle, "modules")
    val sitePackages: File = File(pythonBundle, "site-packages")

    // ── 动态库目录 ──────────────────────────────────────────────────────────
    // Android 安装的原生库目录（只读且可执行，不受 W^X 限制）
    val nativeLibDir: File = File(context.applicationInfo.nativeLibraryDir)

    // ── Python 可执行文件 ────────────────────────────────────────────────────
    // bin/python3 以 libpython3exec.so 形式安装到 nativeLibraryDir（可执行且不受 W^X 限制）
    val pythonBin: File = File(nativeLibDir, "libpython3exec.so")

    // ── 辅助路径 ─────────────────────────────────────────────────────────────
    val pipCache: File    = File(context.cacheDir, "pip-cache")
    val versionFile: File = File(pythonRoot, ".version")
    val tmpDir: File      = File(context.cacheDir, "python-tmp")
    val homeDir: File     = context.filesDir

    // pip_runner.py：从 assets 复制到 filesDir，用于替代 -m pip 以绕过 sysconfig SIGSEGV
    val pipRunnerFile: File = File(homeDir, "pip_runner.py")

    // ── 运行时环境变量 ────────────────────────────────────────────────────────
    /**
     * 返回执行 Python 进程所需的完整环境变量 Map。
     * 调用方通过 ProcessBuilder.environment().putAll(envVars) 注入。
     */
    fun buildEnvVars(): Map<String, String> = mapOf(
        // PYTHONHOME 必须存在且包含 lib/python3.11 子目录，否则 CPython 报 "Could not find platform libraries"
        // 我们把 pythonRoot 作为 prefix，并在 extractAssetZip 后创建该目录
        "PYTHONHOME"               to pythonRoot.absolutePath,
        // PYTHONPATH 优先于 PYTHONHOME 的默认路径，直接指向 p4a 的 bundle 结构
        "PYTHONPATH"               to "${stdlibZip.absolutePath}:${modules.absolutePath}:${sitePackages.absolutePath}",
        // p4a 环境变量
        "ANDROID_PRIVATE"          to pythonRoot.absolutePath,
        "ANDROID_UNPACK"           to pythonRoot.absolutePath,
        "ANDROID_APP_PATH"         to pythonBundle.absolutePath,
        "LD_LIBRARY_PATH"          to "${nativeLibDir.absolutePath}:${modules.absolutePath}",
        "HOME"                     to homeDir.absolutePath,
        "TMPDIR"                   to tmpDir.absolutePath,
        "PYTHONDONTWRITEBYTECODE"  to "1",
        "PYTHONUNBUFFERED"         to "1",
        // 禁用用户 site-packages：p4a Python 中 site.getusersitepackages() 会 SIGSEGV
        "PYTHONNOUSERSITE"         to "1"
    )
}
