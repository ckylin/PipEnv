package com.kylin.pip.env.python

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

private const val TAG = "PythonEnvManager"

sealed class EnvState {
    object NotInitialized : EnvState()
    data class Extracting(val progress: Float) : EnvState()
    object Ready : EnvState()
    data class Error(val message: String) : EnvState()
}

/**
 * 管理 p4a Python 环境的生命周期：解压、版本检查、权限设置、验证。
 */
class PythonEnvManager(
    private val context: Context,
    val config: PythonEnvConfig = PythonEnvConfig(context)
) {

    private val _envState = MutableStateFlow<EnvState>(EnvState.NotInitialized)
    val envState: StateFlow<EnvState> = _envState.asStateFlow()

    /**
     * 确保 Python 环境就绪。已就绪则直接返回，否则执行解压流程。
     * 在 Dispatchers.IO 协程中调用。
     */
    suspend fun ensureReady() = withContext(Dispatchers.IO) {
        if (_envState.value is EnvState.Ready) return@withContext

        try {
            if (isVersionMatch()) {
                Log.i(TAG, "Python env already up-to-date, skipping extraction")
                // 确保 pip_runner.py 存在（可能被用户手动删除）
                if (!config.pipRunnerFile.exists()) {
                    context.assets.open("pip_runner.py").use { input ->
                        config.pipRunnerFile.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                _envState.value = EnvState.Ready
                return@withContext
            }

            // 清理旧版本
            if (config.pythonRoot.exists()) {
                config.pythonRoot.deleteRecursively()
                Log.i(TAG, "Deleted old python root")
            }

            // 解压
            extractAssetZip()

            // 创建 CPython PYTHONHOME 所需的目录结构（空目录即可让路径检查通过）
            File(config.pythonRoot, "lib/python3.11/lib-dynload").mkdirs()

            // 设置可执行权限
            setExecutablePermissions()

            // 写版本文件
            config.versionFile.writeText(config.buildVersion)

            // 复制 pip_runner.py（每次重新解压时同步更新）
            context.assets.open("pip_runner.py").use { input ->
                config.pipRunnerFile.outputStream().use { output -> input.copyTo(output) }
            }
            Log.i(TAG, "Copied pip_runner.py to ${config.pipRunnerFile.absolutePath}")

            // 验证
            verifyInstallation()

            _envState.value = EnvState.Ready
            Log.i(TAG, "Python env ready")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Python env", e)
            config.pythonRoot.deleteRecursively()
            _envState.value = EnvState.Error(e.message ?: "Unknown error")
            throw e
        }
    }

    private fun isVersionMatch(): Boolean {
        if (!config.versionFile.exists()) return false
        return config.versionFile.readText().trim() == config.buildVersion
    }

    private fun extractAssetZip() {
        val assetManager = context.assets
        val zipName = config.assetZipName

        // 计算条目总数用于进度
        val totalEntries = assetManager.open(zipName).use { input ->
            ZipInputStream(input).use { zis ->
                var count = 0
                while (zis.nextEntry != null) { count++; zis.closeEntry() }
                count
            }
        }

        Log.i(TAG, "Extracting $zipName ($totalEntries entries) to ${config.pythonRoot}")
        _envState.value = EnvState.Extracting(0f)

        assetManager.open(zipName).use { input ->
            ZipInputStream(input).use { zis ->
                var processed = 0
                var entry = zis.nextEntry
                while (entry != null) {
                    val outFile = File(config.pythonRoot, entry.name)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { out -> zis.copyTo(out) }
                    }
                    zis.closeEntry()
                    processed++
                    if (totalEntries > 0) {
                        _envState.value = EnvState.Extracting(processed.toFloat() / totalEntries)
                    }
                    entry = zis.nextEntry
                }
            }
        }
    }

    private fun setExecutablePermissions() {
        // pythonBin 位于 nativeLibraryDir，由系统安装，无需手动设置权限
        // 对解压到 filesDir 的其他文件设置可执行权限（如 pip 脚本等）
        Runtime.getRuntime().exec(arrayOf("chmod", "-R", "755", config.pythonRoot.absolutePath))
            .waitFor()
        Log.i(TAG, "Set executable permissions on ${config.pythonRoot.absolutePath}")
    }

    private fun verifyInstallation() {
        Log.i(TAG, "Verifying python at ${config.pythonBin}")

        val process = ProcessBuilder(config.pythonBin.absolutePath, "--version")
            .apply {
                environment().putAll(config.buildEnvVars())
                redirectErrorStream(true)
            }
            .start()

        val output = process.inputStream.bufferedReader().readText().trim()
        val exitCode = process.waitFor()
        Log.i(TAG, "python --version exit=$exitCode output='$output'")

        if (!output.contains("Python 3.")) {
            throw IllegalStateException("Python verification failed (exit=$exitCode): $output")
        }

        // ── 诊断：逐步缩小 pip 崩溃范围 ──────────────────────────────────────
        // 每步独立进程，找到第一个 exit!=0 的步骤即为崩溃点
        val patchPrefix = """
            import sysconfig
            sysconfig.get_paths          = lambda *a, **k: {}
            sysconfig.get_path           = lambda *a, **k: ''
            sysconfig.get_default_scheme = lambda: 'posix_prefix'
            sysconfig.get_scheme_names   = lambda: ('posix_prefix',)
            import site
            site.getusersitepackages = lambda: None
            site.USER_SITE           = None
            site.ENABLE_USER_SITE    = False
        """.trimIndent()

        val diagSteps = listOf(
            // 1. Python 基础是否正常
            "print('step1: python ok')" to "step1",
            // 2. import sysconfig 本身是否崩溃
            "import sysconfig; print('step2: sysconfig ok')" to "step2",
            // 3. 打补丁是否可行
            "$patchPrefix\nprint('step3: patch ok')" to "step3",
            // 4. 打补丁后 _sysconfig 是否还崩溃
            "$patchPrefix\nfrom pip._internal.locations import _sysconfig; print('step4: _sysconfig ok')" to "step4",
            // 5. 打补丁后能否导入完整 locations 包
            "$patchPrefix\nfrom pip._internal import locations; print('step5: locations ok')" to "step5",
            // 6. 打补丁后能否导入 pip.main
            "$patchPrefix\nfrom pip._internal.cli.main import main; print('step6: pip.main ok')" to "step6",
            // 7. pip_runner.py 本身能否执行（只打印，不安装）
            "import sys; sys.argv=['pip','--version']; exec(open('${config.pipRunnerFile.absolutePath}').read())" to "step7"
        )

        for ((code, label) in diagSteps) {
            val p = ProcessBuilder(config.pythonBin.absolutePath, "-c", code).apply {
                environment().putAll(config.buildEnvVars())
                redirectErrorStream(true)
            }.start()
            val out = p.inputStream.bufferedReader().readText().trim()
            val exit = p.waitFor()
            Log.i(TAG, "diag $label exit=$exit: $out")
            if (exit != 0) {
                Log.e(TAG, "CRASH at $label — stopping diag")
                break
            }
        }
    }
}
