package com.kylin.pip.env.python

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "PipInstaller"

sealed class InstallState {
    object Idle : InstallState()
    data class Installing(val packageName: String, val log: String) : InstallState()
    data class Success(val packageName: String) : InstallState()
    data class Failed(val packageName: String, val error: String) : InstallState()
}

/**
 * 封装 `python3 -m pip install` 命令，安装到 App 私有 site-packages。
 */
class PipInstaller(private val config: PythonEnvConfig) {

    private val installMutex = Mutex()

    /**
     * 安装指定包，返回最终 InstallState（Success 或 Failed）。
     * 同一时刻只允许一个安装任务运行（Mutex 保护）。
     */
    suspend fun install(packageName: String): InstallState = withContext(Dispatchers.IO) {
        installMutex.withLock {
            Log.i(TAG, "Installing $packageName")
            config.sitePackages.mkdirs()
            config.pipCache.mkdirs()

            val cmd = listOf(
                config.pythonBin.absolutePath,
                "-S",  // 跳过 site.py/sitecustomize.py，防止 jnius 在 subprocess 中 SIGSEGV
                config.pipRunnerFile.absolutePath,
                "install",
                packageName,
                "--target", config.sitePackages.absolutePath,
                "--cache-dir", config.pipCache.absolutePath,
                "--no-warn-script-location",
                "--disable-pip-version-check",
                "--only-binary", ":all:"
            )

            val process = ProcessBuilder(cmd).apply {
                environment().putAll(config.buildEnvVars())
                directory(config.homeDir)
                redirectErrorStream(true)
            }.start()

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            Log.i(TAG, "pip install $packageName exit=$exitCode\n$output")

            if (exitCode == 0) {
                InstallState.Success(packageName)
            } else {
                InstallState.Failed(packageName, output)
            }
        }
    }

    /**
     * 流式安装，逐行 emit pip 输出日志。
     * 收集完成后最后一行为 "__EXIT__:<exitCode>"，供调用方判断成功/失败。
     */
    fun installStreaming(packageName: String): Flow<String> = flow {
        installMutex.withLock {
            config.sitePackages.mkdirs()
            config.pipCache.mkdirs()

            val cmd = listOf(
                config.pythonBin.absolutePath,
                "-S",  // 跳过 site.py/sitecustomize.py，防止 jnius 在 subprocess 中 SIGSEGV
                config.pipRunnerFile.absolutePath,
                "install",
                packageName,
                "--target", config.sitePackages.absolutePath,
                "--cache-dir", config.pipCache.absolutePath,
                "--no-warn-script-location",
                "--disable-pip-version-check",
                "--only-binary", ":all:"
            )

            val process = ProcessBuilder(cmd).apply {
                environment().putAll(config.buildEnvVars())
                directory(config.homeDir)
                redirectErrorStream(true)
            }.start()

            val reader = process.inputStream.bufferedReader()
            while (true) {
                val line = reader.readLine() ?: break
                emit(line)
            }

            val exitCode = process.waitFor()
            emit("__EXIT__:$exitCode")
        }
    }.flowOn(Dispatchers.IO)
}
