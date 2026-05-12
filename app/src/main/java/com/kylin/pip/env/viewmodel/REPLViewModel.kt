package com.kylin.pip.env.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kylin.pip.env.python.EnvState
import com.kylin.pip.env.python.InstallState
import com.kylin.pip.env.python.PipInstaller
import com.kylin.pip.env.python.PythonEnvConfig
import com.kylin.pip.env.python.PythonEnvManager
import com.kylin.pip.env.python.PythonExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "REPLViewModel"

// 匹配 "pip install <package>" 或 "pip3 install <package>"
private val PIP_INSTALL_REGEX = Regex("""^\s*pip3?\s+install\s+(.+)""")

data class ReplLine(
    val text: String,
    val isInput: Boolean = false,
    val isError: Boolean = false
)

class REPLViewModel(application: Application) : AndroidViewModel(application) {

    private val config = PythonEnvConfig(application)
    val envManager = PythonEnvManager(application, config)
    private val executor = PythonExecutor(config)
    private val pipInstaller = PipInstaller(config)

    val envState: StateFlow<EnvState> = envManager.envState

    private val _outputLines = MutableStateFlow<List<ReplLine>>(emptyList())
    val outputLines: StateFlow<List<ReplLine>> = _outputLines.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _installState = MutableStateFlow<InstallState>(InstallState.Idle)
    val installState: StateFlow<InstallState> = _installState.asStateFlow()

    private var executionJob: Job? = null

    // session_wrapper.py 在 filesDir 中的路径（首次使用时从 assets 复制）
    private val sessionWrapperFile: File = File(application.filesDir, "session_wrapper.py")
    private val sessionFile: File = File(application.filesDir, "repl_session")

    init {
        // 初始化 Python 环境
        viewModelScope.launch(Dispatchers.IO) {
            try {
                envManager.ensureReady()
                copySessionWrapper()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize Python env", e)
            }
        }
    }

    /** 将 assets/session_wrapper.py 复制到 filesDir（仅首次或更新时） */
    private fun copySessionWrapper() {
        if (!sessionWrapperFile.exists()) {
            getApplication<Application>().assets.open("session_wrapper.py").use { input ->
                sessionWrapperFile.outputStream().use { output -> input.copyTo(output) }
            }
            Log.i(TAG, "Copied session_wrapper.py to ${sessionWrapperFile.absolutePath}")
        }
    }

    /**
     * 执行用户输入的代码或命令。
     * - 匹配 pip install → PipInstaller
     * - 其他 → PythonExecutor（通过 session_wrapper.py 保持会话）
     */
    fun execute(input: String) {
        if (_isRunning.value) return
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return

        appendLine(ReplLine(">>> $trimmed", isInput = true))

        val pipMatch = PIP_INSTALL_REGEX.find(trimmed)
        if (pipMatch != null) {
            val packageName = pipMatch.groupValues[1].trim()
            runPipInstall(packageName)
        } else {
            runPythonCode(trimmed)
        }
    }

    private fun runPythonCode(code: String) {
        executionJob = viewModelScope.launch {
            _isRunning.value = true
            try {
                executor.executeScriptStreaming(
                    scriptFile = sessionWrapperFile,
                    args = listOf(sessionFile.absolutePath, code)
                ).collect { line ->
                    appendLine(ReplLine(line))
                }
            } catch (e: Exception) {
                appendLine(ReplLine("Error: ${e.message}", isError = true))
            } finally {
                _isRunning.value = false
            }
        }
    }

    private fun runPipInstall(packageName: String) {
        Log.i(TAG, "install $packageName start...")
        executionJob = viewModelScope.launch {
            _isRunning.value = true
            _installState.value = InstallState.Installing(packageName, "")
            try {
                pipInstaller.installStreaming(packageName).collect { line ->
                    Log.i(TAG, line)
                    if (line.startsWith("__EXIT__:")) {
                        val exitCode = line.removePrefix("__EXIT__:").toIntOrNull() ?: -1
                        _installState.value = if (exitCode == 0) {
                            appendLine(ReplLine("Successfully installed $packageName"))
                            InstallState.Success(packageName)
                        } else {
                            InstallState.Failed(packageName, "exit code $exitCode")
                        }
                    } else {
                        appendLine(ReplLine(line))
                        _installState.value = InstallState.Installing(packageName, line)
                    }
                }
            } catch (e: Exception) {
                Log.i(TAG, "pip error: ${e.message}")
                appendLine(ReplLine("pip error: ${e.message}", isError = true))
                _installState.value = InstallState.Failed(packageName, e.message ?: "")
            } finally {
                _isRunning.value = false
            }
        }
    }

    /** 中断当前执行 */
    fun cancelExecution() {
        executor.cancelExecution()
        executionJob?.cancel()
        executionJob = null
        _isRunning.value = false
        appendLine(ReplLine("KeyboardInterrupt", isError = true))
    }

    /** 清空输出 */
    fun clearOutput() {
        _outputLines.value = emptyList()
    }

    /** 清除会话变量（删除 shelve 文件） */
    fun clearSession() {
        listOf("", ".db", ".dir", ".bak").forEach { ext ->
            File("${sessionFile.absolutePath}$ext").delete()
        }
        appendLine(ReplLine("Session cleared."))
    }

    private fun appendLine(line: ReplLine) {
        _outputLines.value = _outputLines.value + line
    }
}
