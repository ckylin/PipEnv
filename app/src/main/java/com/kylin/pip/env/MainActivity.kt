package com.kylin.pip.env

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kylin.pip.env.python.EnvState
import com.kylin.pip.env.ui.theme.PipEnvTheme
import com.kylin.pip.env.viewmodel.REPLViewModel
import com.kylin.pip.env.viewmodel.ReplLine

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PipEnvTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ReplScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun ReplScreen(
    modifier: Modifier = Modifier,
    vm: REPLViewModel = viewModel()
) {
    val envState by vm.envState.collectAsState()
    val outputLines by vm.outputLines.collectAsState()
    val isRunning by vm.isRunning.collectAsState()
    val listState = rememberLazyListState()

    // 新输出时自动滚动到底部
    LaunchedEffect(outputLines.size) {
        if (outputLines.isNotEmpty()) {
            listState.animateScrollToItem(outputLines.size - 1)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
    ) {
        // 环境状态栏
        when (val state = envState) {
            is EnvState.NotInitialized -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                    Text("正在准备 Python 环境...")
                }
            }
            is EnvState.Extracting -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(8.dp)
                ) {
                    Text("正在解压 Python 运行时 (${(state.progress * 100).toInt()}%)...")
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    )
                }
            }
            is EnvState.Error -> {
                Text(
                    text = "初始化失败：${state.message}",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(8.dp)
                )
            }
            is EnvState.Ready -> { /* 不显示状态栏 */ }
        }

        // 输出区域
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF1E1E1E))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            items(outputLines) { line ->
                OutputLineView(line)
            }
            if (isRunning) {
                item {
                    Text(
                        text = "▌",
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp
                    )
                }
            }
        }

        // 输入区域
        InputBar(
            enabled = envState is EnvState.Ready,
            isRunning = isRunning,
            onExecute = { vm.execute(it) },
            onCancel = { vm.cancelExecution() },
            onClear = { vm.clearOutput() },
            onClearSession = { vm.clearSession() }
        )
    }
}

@Composable
private fun OutputLineView(line: ReplLine) {
    val color = when {
        line.isInput -> Color(0xFF4FC3F7)   // 蓝色：用户输入
        line.isError -> Color(0xFFEF9A9A)   // 红色：错误
        else         -> Color(0xFFE0E0E0)   // 灰白：普通输出
    }
    Text(
        text = line.text,
        color = color,
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun InputBar(
    enabled: Boolean,
    isRunning: Boolean,
    onExecute: (String) -> Unit,
    onCancel: () -> Unit,
    onClear: () -> Unit,
    onClearSession: () -> Unit
) {
    var input by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                enabled = enabled && !isRunning,
                placeholder = { Text(if (enabled) "输入 Python 代码..." else "等待环境初始化...") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (input.isNotBlank()) {
                        onExecute(input)
                        input = ""
                    }
                })
            )
            if (isRunning) {
                Button(onClick = onCancel) { Text("停止") }
            } else {
                Button(
                    onClick = {
                        if (input.isNotBlank()) {
                            onExecute(input)
                            input = ""
                        }
                    },
                    enabled = enabled && input.isNotBlank()
                ) { Text("运行") }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onClear) { Text("清空输出") }
            TextButton(onClick = onClearSession) { Text("清除会话") }
        }
    }
}
