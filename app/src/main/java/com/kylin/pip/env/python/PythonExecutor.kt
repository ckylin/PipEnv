package com.kylin.pip.env.python

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

private const val TAG = "PythonExecutor"
private const val DEFAULT_TIMEOUT_MS = 30_000L

data class PythonResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
) {
    val isSuccess: Boolean get() = exitCode == 0
}

/**
 * 封装 ProcessBuilder，执行 Python 脚本或代码字符串。
 */
class PythonExecutor(private val config: PythonEnvConfig) {

    @Volatile
    private var currentProcess: Process? = null

    /**
     * 执行代码字符串，返回完整结果（阻塞直到进程结束）。
     * timeoutMs <= 0 表示不限时。
     */
    suspend fun execute(
        code: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): PythonResult = withContext(Dispatchers.IO) {
        val process = buildProcess(listOf(config.pythonBin.absolutePath, "-u", "-c", code))
        currentProcess = process

        val stdoutJob: Job
        val stderrJob: Job
        val stdoutBuf = StringBuilder()
        val stderrBuf = StringBuilder()

        stdoutJob = launch {
            process.inputStream.bufferedReader().forEachLine { stdoutBuf.appendLine(it) }
        }
        stderrJob = launch {
            process.errorStream.bufferedReader().forEachLine { stderrBuf.appendLine(it) }
        }

        val exitCode: Int
        if (timeoutMs > 0) {
            val result = withTimeoutOrNull(timeoutMs) {
                process.waitFor()
            }
            if (result == null) {
                process.destroyForcibly()
                Log.w(TAG, "Process timed out after ${timeoutMs}ms")
            }
            exitCode = try { process.exitValue() } catch (e: IllegalThreadStateException) { -1 }
        } else {
            exitCode = process.waitFor()
        }

        stdoutJob.cancelAndJoin()
        stderrJob.cancelAndJoin()
        currentProcess = null

        PythonResult(exitCode, stdoutBuf.toString(), stderrBuf.toString())
    }

    /**
     * 执行脚本文件，返回完整结果。
     */
    suspend fun executeScript(
        scriptFile: File,
        args: List<String> = emptyList(),
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): PythonResult = withContext(Dispatchers.IO) {
        val cmd = mutableListOf(config.pythonBin.absolutePath, "-u", scriptFile.absolutePath)
        cmd.addAll(args)
        val process = buildProcess(cmd)
        currentProcess = process

        val stdoutBuf = StringBuilder()
        val stderrBuf = StringBuilder()

        val stdoutJob = launch {
            process.inputStream.bufferedReader().forEachLine { stdoutBuf.appendLine(it) }
        }
        val stderrJob = launch {
            process.errorStream.bufferedReader().forEachLine { stderrBuf.appendLine(it) }
        }

        val exitCode: Int
        if (timeoutMs > 0) {
            val result = withTimeoutOrNull(timeoutMs) { process.waitFor() }
            if (result == null) process.destroyForcibly()
            exitCode = try { process.exitValue() } catch (e: IllegalThreadStateException) { -1 }
        } else {
            exitCode = process.waitFor()
        }

        stdoutJob.cancelAndJoin()
        stderrJob.cancelAndJoin()
        currentProcess = null

        PythonResult(exitCode, stdoutBuf.toString(), stderrBuf.toString())
    }

    /**
     * 流式执行代码字符串，逐行 emit stdout（stderr 合并到 stdout 流）。
     * 适合 REPL 场景实时显示输出。
     */
    fun executeStreaming(code: String): Flow<String> = flow {
        val process = buildProcess(
            listOf(config.pythonBin.absolutePath, "-u", "-c", code),
            mergeStderr = true
        )
        currentProcess = process
        try {
            val reader = process.inputStream.bufferedReader()
            while (true) {
                val line = reader.readLine() ?: break
                emit(line)
            }
            process.waitFor()
        } finally {
            currentProcess = null
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 流式执行脚本文件，逐行 emit stdout+stderr。
     */
    fun executeScriptStreaming(
        scriptFile: File,
        args: List<String> = emptyList()
    ): Flow<String> = flow {
        val cmd = mutableListOf(config.pythonBin.absolutePath, "-u", scriptFile.absolutePath)
        cmd.addAll(args)
        val process = buildProcess(cmd, mergeStderr = true)
        currentProcess = process
        try {
            val reader = process.inputStream.bufferedReader()
            while (true) {
                val line = reader.readLine() ?: break
                emit(line)
            }
            process.waitFor()
        } finally {
            currentProcess = null
        }
    }.flowOn(Dispatchers.IO)

    /** 强制终止当前正在运行的进程 */
    fun cancelExecution() {
        currentProcess?.destroyForcibly()
        currentProcess = null
    }

    private fun buildProcess(cmd: List<String>, mergeStderr: Boolean = false): Process {
        config.tmpDir.mkdirs()
        config.sitePackages.mkdirs()
        return ProcessBuilder(cmd).apply {
            environment().putAll(config.buildEnvVars())
            directory(config.homeDir)
            redirectErrorStream(mergeStderr)
        }.start()
    }
}
