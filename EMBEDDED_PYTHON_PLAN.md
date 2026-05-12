# 内嵌独立 Python 运行时方案（python-for-android）

> 目标：使用 python-for-android（p4a）为 Android 构建完整的 Python 运行时，打包进 App assets，通过 `ProcessBuilder` 调用执行任意脚本和 `pip install`，实现自由安装 Python 包。
>
> 项目信息：`com.kylin.pyenv`，Python 3.12，ABI：`arm64-v8a` / `x86_64`，开发机：Windows + WSL2

---

## 目录

1. [整体架构](#整体架构)
2. [阶段一：构建环境搭建（WSL2）](#阶段一构建环境搭建wsl2)
3. [阶段二：使用 p4a 构建 Python 发行版](#阶段二使用-p4a-构建-python-发行版)
4. [阶段三：打包产物到 Android 项目](#阶段三打包产物到-android-项目)
5. [阶段四：Android 模块划分](#阶段四android-模块划分)
6. [阶段五：PythonEnvConfig — 路径与环境变量](#阶段五pythonenvconfig--路径与环境变量)
7. [阶段六：PythonEnvManager — 环境初始化](#阶段六pythonenvmanager--环境初始化)
8. [阶段七：PythonExecutor — 脚本执行](#阶段七pythonexecutor--脚本执行)
9. [阶段八：PipInstaller — pip install 封装](#阶段八pipinstaller--pip-install-封装)
10. [阶段九：与现有 REPLViewModel 集成](#阶段九与现有-replviewmodel-集成)
11. [阶段十：AndroidManifest 权限](#阶段十androidmanifest-权限)
12. [整体数据流](#整体数据流)
13. [主要风险点](#主要风险点)
14. [参考资源](#参考资源)

---

## 整体架构

```
┌─────────────────────────────────────────────────────┐
│  构建阶段（WSL2 / Linux）                             │
│                                                     │
│  python-for-android                                 │
│    └── p4a apk / toolchain                          │
│          ├── 编译 Python 3.12 for arm64-v8a         │
│          ├── 编译 Python 3.12 for x86_64            │
│          └── 打包 stdlib + pip + site-packages      │
│                    ↓                                │
│          python-3.12-arm64-v8a.zip                  │
│          python-3.12-x86_64.zip                     │
└─────────────────────────────────────────────────────┘
                      ↓ 复制到项目
┌─────────────────────────────────────────────────────┐
│  Android 项目（app/src/main/assets/）                │
│                                                     │
│  首次启动 → 解压到 context.filesDir/python/          │
│  运行时   → ProcessBuilder 调用 python3.12           │
│           → pip install <package>                   │
│           → 执行用户脚本                             │
└─────────────────────────────────────────────────────┘
```

---

## 阶段一：构建环境搭建（WSL2）

p4a 只能在 Linux / macOS 上运行，Windows 需要通过 WSL2。

### 1.1 安装 WSL2

```powershell
# 在 Windows PowerShell（管理员）中执行
wsl --install -d Ubuntu-22.04
```

安装完成后重启，进入 Ubuntu 22.04 终端。

### 1.2 安装系统依赖

```bash
sudo apt update && sudo apt install -y \
    python3 python3-pip python3-venv \
    git zip unzip \
    build-essential \
    libffi-dev libssl-dev \
    autoconf automake libtool \
    pkg-config cmake \
    openjdk-17-jdk
```

### 1.3 安装 Android SDK 和 NDK

p4a 需要 Android SDK（含 build-tools）和 NDK。通过官方 command-line tools 中的 `sdkmanager` 安装。

#### 步骤一：下载并解压 command-line tools

```bash
# 创建 SDK 根目录
mkdir -p ~/android-sdk/cmdline-tools

# 下载 command-line tools（包含 sdkmanager）
# 最新版本号可在 https://developer.android.com/studio#command-tools 查看
cd ~/android-sdk/cmdline-tools
wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip

# 解压
unzip commandlinetools-linux-*.zip

# 必须将解压出的目录重命名为 latest，否则 sdkmanager 无法正常工作
# 解压后目录名为 cmdline-tools，需移动到 latest 子目录
mv cmdline-tools latest

# 验证目录结构是否正确
# 正确结构：~/android-sdk/cmdline-tools/latest/bin/sdkmanager
ls ~/android-sdk/cmdline-tools/latest/bin/
```

> **常见错误**：如果不做 `mv cmdline-tools latest` 这一步，`sdkmanager` 会报错：
> `Warning: Could not create settings`

#### 步骤二：配置环境变量

```bash
echo 'export ANDROID_HOME=$HOME/android-sdk' >> ~/.bashrc
echo 'export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin' >> ~/.bashrc
echo 'export PATH=$PATH:$ANDROID_HOME/platform-tools' >> ~/.bashrc
source ~/.bashrc

# 验证 sdkmanager 可用
sdkmanager --version
```

#### 步骤三：接受许可证

```bash
# 接受所有 Android SDK 许可证（需要手动输入 y 多次，或用 yes 命令自动确认）
yes | sdkmanager --licenses
```

#### 步骤四：安装 SDK 组件

```bash
# 安装 platform-tools（含 adb）、平台 API、build-tools
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"

# 验证安装
adb --version
```

#### 步骤五：安装 NDK r25b

p4a 对 NDK 版本敏感，推荐使用 r25b（25.2.9519653）：

```bash
sdkmanager "ndk;25.2.9519653"

# 验证 NDK 安装路径
ls ~/android-sdk/ndk/25.2.9519653/
```

> **注意**：NDK 完整路径 `~/android-sdk/ndk/25.2.9519653` 在后续 p4a 构建命令中通过 `--ndk-dir` 参数传入。不要使用 NDK r26+ 或 r24-，p4a 对这些版本的兼容性较差。

### 1.4 安装 python-for-android

```bash
# 创建独立虚拟环境，避免污染系统 Python
python3 -m venv ~/p4a-env
source ~/p4a-env/bin/activate

pip install python-for-android

# 验证安装
p4a --version
```

---

## 阶段二：使用 p4a 构建 Python 发行版

### 2.1 p4a 的核心概念

| 概念 | 说明 |
|------|------|
| **Recipe** | 每个包（Python、openssl、pip 等）的编译脚本 |
| **Bootstrap** | App 的宿主框架，`service_only` 是最小化无 UI 框架 |
| **Distribution** | 编译完成的 Python 运行时 + 所选包的集合 |
| **Requirements** | 需要编译进发行版的包列表 |

### 2.2 构建命令

```bash
source ~/p4a-env/bin/activate

# 构建 arm64-v8a
p4a create \
  --arch arm64-v8a \
  --python-version 3.12 \
  --bootstrap service_only \
  --requirements python3,pip,setuptools,wheel \
  --dist-name pyenv_arm64 \
  --sdk-dir $ANDROID_HOME \
  --ndk-dir $ANDROID_HOME/ndk/25.2.9519653 \
  --android-api 33

# 构建 x86_64（模拟器用）
p4a create \
  --arch x86_64 \
  --python-version 3.12 \
  --bootstrap service_only \
  --requirements python3,pip,setuptools,wheel \
  --dist-name pyenv_x86_64 \
  --sdk-dir $ANDROID_HOME \
  --ndk-dir $ANDROID_HOME/ndk/25.2.9519653 \
  --android-api 33
```

> 首次构建需要下载并编译 Python 源码，耗时约 20~40 分钟，后续增量构建很快。

### 2.3 构建产物位置

p4a 的产物分布在两个目录，需要分别取用：

**dist 目录**（`~/.local/share/python-for-android/dists/pyenv_arm64/`）：

```
dists/pyenv_arm64/
├── _python_bundle__arm64-v8a/       # 注意：目录名带 ABI 后缀
│   └── _python_bundle/              # 内部还有一层同名子目录
│       ├── modules/                 # 编译好的 .so 扩展模块
│       ├── site-packages/           # pip、setuptools、wheel 等
│       └── stdlib.zip               # Python 标准库（压缩包）
└── libs/
    └── arm64-v8a/
        ├── libpython3.11.so         # Python 动态库（当前 p4a 稳定版为 3.11）
        ├── libcrypto1.1.so
        ├── libssl1.1.so
        ├── libffi.so
        └── libsqlite3.so
```

**build 目录**（Python 可执行文件在此，dist 中没有）：

```
build/other_builds/python3/arm64-v8a__ndk_target_21/python3/android-build/
└── python                           # ARM64 ELF 可执行文件，需要单独取出
```

> **重要**：p4a 的 dist 目录不包含 `python` 可执行文件，必须从 build 目录中单独提取。

### 2.4 打包为 zip

需要将 dist 产物和 build 目录中的可执行文件合并打包：

```bash
#!/bin/bash
# pack_python.sh

P4A_HOME=~/.local/share/python-for-android
DIST_ARM64=$P4A_HOME/dists/pyenv_arm64
DIST_X86_64=$P4A_HOME/dists/pyenv_x86_64
BUILD_ARM64=$P4A_HOME/build/other_builds/python3/arm64-v8a__ndk_target_21/python3/android-build
BUILD_X86_64=$P4A_HOME/build/other_builds/python3/x86_64__ndk_target_21/python3/android-build
OUTPUT_DIR=~/python-android-dist

mkdir -p $OUTPUT_DIR

pack_abi() {
  local ABI=$1
  local DIST=$2
  local BUILD=$3
  local OUT=$OUTPUT_DIR/python-3.11-${ABI}.zip

  # 创建临时目录，统一目录结构
  local TMP=$(mktemp -d)

  # 复制 _python_bundle（展平一层，去掉 ABI 后缀目录）
  cp -r $DIST/_python_bundle__${ABI}/_python_bundle $TMP/_python_bundle

  # 复制 .so 动态库
  mkdir -p $TMP/libs/$ABI
  cp $DIST/libs/$ABI/*.so $TMP/libs/$ABI/

  # 复制 python 可执行文件
  mkdir -p $TMP/bin
  cp $BUILD/python $TMP/bin/python3
  chmod +x $TMP/bin/python3

  # 打包
  cd $TMP && zip -r $OUT . && cd -
  rm -rf $TMP

  echo "打包完成：$OUT"
}

pack_abi "arm64-v8a" $DIST_ARM64 $BUILD_ARM64
pack_abi "x86_64"    $DIST_X86_64 $BUILD_X86_64
```

打包后 zip 内的统一目录结构：

```
python-3.11-arm64-v8a.zip
├── _python_bundle/
│   ├── modules/
│   ├── site-packages/
│   └── stdlib.zip
├── libs/
│   └── arm64-v8a/
│       ├── libpython3.11.so
│       ├── libcrypto1.1.so
│       ├── libssl1.1.so
│       ├── libffi.so
│       └── libsqlite3.so
└── bin/
    └── python3                      # 可执行文件
```

### 2.5 裁剪体积（可选）

p4a 的 stdlib.zip 包含大量不需要的模块，可在打包前裁剪：

```bash
STDLIB=$DIST_ARM64/_python_bundle__arm64-v8a/_python_bundle/stdlib.zip

mkdir -p stdlib_tmp
unzip -q $STDLIB -d stdlib_tmp
rm -rf stdlib_tmp/test stdlib_tmp/tkinter stdlib_tmp/idlelib \
       stdlib_tmp/turtledemo stdlib_tmp/turtle.py \
       stdlib_tmp/ensurepip stdlib_tmp/distutils
cd stdlib_tmp && zip -qr ../stdlib_trimmed.zip . && cd ..
mv stdlib_trimmed.zip $STDLIB
rm -rf stdlib_tmp
```

裁剪后 stdlib.zip 体积可从 ~15MB 降至 ~8MB。

---

## 阶段三：打包产物到 Android 项目

### 3.1 从 WSL2 复制到 Windows

```bash
# WSL2 中执行，将 zip 复制到 Windows 项目目录
# WSL2 访问 Windows 路径通过 /mnt/c/
cp ~/python-android-dist/python-3.12-arm64-v8a.zip \
   "/mnt/c/Users/c1kyl/AndroidStudioProjects/PyEnv/app/src/main/assets/"

cp ~/python-android-dist/python-3.12-x86_64.zip \
   "/mnt/c/Users/c1kyl/AndroidStudioProjects/PyEnv/app/src/main/assets/"
```

### 3.2 项目 assets 目录结构

```
app/src/main/assets/
├── python-3.12-arm64-v8a.zip    # 真机（主流）
└── python-3.12-x86_64.zip       # 模拟器
```

### 3.3 p4a 产物解压后的目录映射

解压到 `context.filesDir/python/` 后的结构：

```
filesDir/python/
├── _python_bundle/
│   ├── modules/              # 扩展模块 .so
│   ├── stdlib.zip            # 标准库
│   └── site-packages/        # 动态安装包的目标目录
├── libs/
│   └── arm64-v8a/            # 或 x86_64/
│       ├── libpython3.12.so
│       ├── libssl.so
│       └── libcrypto.so
└── python-installs/
    └── arm64-v8a/
        └── bin/
            └── python3.12    # 主可执行文件
```

---

## 阶段四：Android 模块划分

```
com.kylin.pyenv/
├── python/
│   ├── PythonEnvConfig.kt      # 路径常量、环境变量配置
│   ├── PythonEnvManager.kt     # 环境生命周期管理（解压、版本检查）
│   ├── PythonExecutor.kt       # ProcessBuilder 封装，执行脚本
│   └── PipInstaller.kt         # pip install 封装
└── viewmodel/
    └── REPLViewModel.kt        # 已有，接入新的 Executor
```

---

## 阶段五：PythonEnvConfig — 路径与环境变量

所有路径基于 p4a 的实际产物目录结构定义。

### 路径定义

```
abi             = Build.SUPPORTED_ABIS[0]   // "arm64-v8a" 或 "x86_64"

pythonRoot      = context.filesDir/python/
pythonBundle    = pythonRoot/_python_bundle/
stdlibZip       = pythonBundle/stdlib.zip
modules         = pythonBundle/modules/
sitePackages    = pythonBundle/site-packages/
nativeLibDir    = pythonRoot/libs/{abi}/
pythonBin       = pythonRoot/python-installs/{abi}/bin/python3.12

pipCache        = context.cacheDir/pip-cache/
versionFile     = pythonRoot/.version
```

### 运行时环境变量

| 变量 | 值 | 说明 |
|------|----|------|
| `PYTHONHOME` | `pythonRoot` | Python 查找标准库的根目录 |
| `PYTHONPATH` | `stdlibZip:modules:sitePackages` | 模块搜索路径，stdlib.zip 直接加入 |
| `LD_LIBRARY_PATH` | `nativeLibDir` | `.so` 动态库加载路径 |
| `HOME` | `context.filesDir` | pip 写入配置文件需要 HOME |
| `TMPDIR` | `context.cacheDir` | 临时文件目录 |
| `PYTHONDONTWRITEBYTECODE` | `1` | 禁止生成 `.pyc`，节省存储 |
| `PYTHONUNBUFFERED` | `1` | 禁用输出缓冲，实时读取 stdout |

> **p4a 特殊点**：标准库以 `stdlib.zip` 形式存在，需要直接加入 `PYTHONPATH`，而非展开的目录。

---

## 阶段六：PythonEnvManager — 环境初始化

### 首次启动流程

```
1. 检测设备 ABI
   → Build.SUPPORTED_ABIS[0]
   → 映射到 assets 中的 zip 文件名：
     "arm64-v8a" → "python-3.12-arm64-v8a.zip"
     "x86_64"    → "python-3.12-x86_64.zip"

2. 检查版本文件 filesDir/python/.version
   → 内容格式："3.12-p4a-build1"
   → 版本匹配则跳过解压，直接进入 Ready 状态

3. 清理旧版本（如有）
   → 递归删除 filesDir/python/ 目录

4. 流式解压 zip 到 filesDir/python/
   → 使用 ZipInputStream 逐条目写入
   → 通过 StateFlow<Float> 暴露进度（0.0 ~ 1.0）
   → 在 Dispatchers.IO 协程中执行

5. 设置可执行权限
   → File(pythonBin).setExecutable(true, false)
   → 遍历 python-installs/{abi}/bin/ 下所有文件

6. 写入版本文件
   → filesDir/python/.version = "3.12-p4a-build1"

7. 验证安装
   → 执行：pythonBin --version
   → 检查输出包含 "Python 3.12"
   → 失败则清理目录并抛出异常，下次启动重试
```

### 状态暴露

```kotlin
sealed class EnvState {
    object NotInitialized : EnvState()
    data class Extracting(val progress: Float) : EnvState()
    object Ready : EnvState()
    data class Error(val message: String) : EnvState()
}

val envState: StateFlow<EnvState>

suspend fun ensureReady()  // 挂起直到 Ready，供其他模块调用
```

---

## 阶段七：PythonExecutor — 脚本执行

### ProcessBuilder 核心配置

```
命令：
  [pythonBin, "-u", scriptPath, ...args]
  或
  [pythonBin, "-u", "-c", codeString]   // 直接执行代码字符串

环境变量：
  val env = ProcessBuilder(...).environment()
  env["PYTHONHOME"]          = pythonRoot
  env["PYTHONPATH"]          = "$stdlibZip:$modules:$sitePackages"
  env["LD_LIBRARY_PATH"]     = nativeLibDir
  env["HOME"]                = context.filesDir.absolutePath
  env["TMPDIR"]              = context.cacheDir.absolutePath
  env["PYTHONDONTWRITEBYTECODE"] = "1"
  env["PYTHONUNBUFFERED"]    = "1"

工作目录：
  context.filesDir

stdout / stderr：
  redirectErrorStream(false)
  两个独立协程并发读取，避免缓冲区满导致死锁
```

### 执行结果封装

```kotlin
data class PythonResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
) {
    val isSuccess: Boolean get() = exitCode == 0
}
```

### 流式输出（REPL 场景）

```kotlin
// 返回 Flow，逐行 emit stdout
fun executeStreaming(code: String): Flow<String>

// UI 层
viewModel.outputLines.collectAsState()
```

### 超时与取消

```kotlin
// 使用 withTimeout 包裹
// 超时或协程取消时调用 process.destroyForcibly()
// 通过 Job 支持用户手动中断执行
val currentJob: Job?
fun cancelExecution() { currentJob?.cancel() }
```

---

## 阶段八：PipInstaller — pip install 封装

### 完整命令

```bash
python3.12 -m pip install <package> \
  --target <sitePackages> \
  --cache-dir <pipCache> \
  --no-warn-script-location \
  --disable-pip-version-check \
  --only-binary :all:
```

### 参数说明

| 参数 | 原因 |
|------|------|
| `-m pip` | 使用内嵌 pip，不依赖系统环境 |
| `--target` | 安装到 App 私有 `_python_bundle/site-packages/` |
| `--cache-dir` | 使用 App 缓存目录，避免写入系统路径 |
| `--no-warn-script-location` | 抑制 scripts 目录相关无关警告 |
| `--disable-pip-version-check` | 避免额外网络请求 |
| `--only-binary :all:` | 只安装预编译 wheel，不尝试编译 C 扩展 |

### 安装含 C 扩展的包

p4a 的优势在于可以在构建阶段就把 C 扩展包编译进发行版（通过 `--requirements`）。对于运行时动态安装，需要 Android 专用 wheel：

```bash
# 方式一：使用 Chaquopy 的 Android wheel 仓库
python3.12 -m pip install numpy \
  --extra-index-url https://chaquo.com/pypi-13.1/ \
  --target <sitePackages>

# 方式二：在 p4a 构建阶段直接编译进发行版（推荐）
p4a create \
  --requirements python3,pip,setuptools,wheel,numpy,pandas \
  ...
```

> **推荐**：常用的 C 扩展包（numpy、pandas 等）在 p4a 构建时通过 `--requirements` 编译进发行版，运行时只用 pip 安装纯 Python 包。

### 安装状态暴露

```kotlin
sealed class InstallState {
    object Idle : InstallState()
    data class Installing(val packageName: String, val log: String) : InstallState()
    data class Success(val packageName: String) : InstallState()
    data class Failed(val packageName: String, val error: String) : InstallState()
}

val installState: StateFlow<InstallState>
```

### 并发保护

```kotlin
private val installMutex = Mutex()

suspend fun install(packageName: String): InstallState {
    return installMutex.withLock {
        // 执行 pip install
    }
}
```

---

## 阶段九：与现有 REPLViewModel 集成

### 当前架构

```
REPLViewModel
  └── runner.py (via Chaquopy)
        └── run_code(code) → exec(code, {}, local_vars)
```

### 集成后架构

由于目标是完全使用 p4a 的 Python 运行时，推荐**完全替换** Chaquopy 的执行路径：

```
REPLViewModel
  ├── PythonEnvManager  ──→  管理 p4a Python 环境生命周期
  ├── PythonExecutor    ──→  执行用户代码（替换 Chaquopy runner.py）
  └── PipInstaller      ──→  处理 pip install 命令
```

### REPLViewModel 改造要点

```
1. 初始化时调用 PythonEnvManager.ensureReady()
   → 在 init 块中启动，通过 envState 驱动 UI loading

2. 执行代码时
   → 将用户输入写入临时脚本文件（filesDir/tmp/repl_input.py）
   → 调用 PythonExecutor.executeStreaming()
   → 收集 Flow<String> 追加到输出列表

3. 检测 pip install 命令
   → 正则匹配 "^\s*pip\s+install\s+(.+)"
   → 匹配则走 PipInstaller.install()，否则走 PythonExecutor

4. 会话状态保持
   → p4a Python 是独立进程，每次执行是新进程
   → 需要将会话变量序列化到文件（shelve 或 pickle）
   → 每次执行前加载，执行后保存
```

### 会话状态保持方案

```python
# session_wrapper.py（打包进 assets 或 python 源码目录）
import shelve, sys, traceback

SESSION_FILE = sys.argv[1]   # 传入 session 文件路径
CODE = sys.argv[2]           # 传入要执行的代码

with shelve.open(SESSION_FILE) as session:
    local_vars = dict(session)
    try:
        exec(compile(CODE, "<repl>", "exec"), local_vars)
    except Exception:
        traceback.print_exc()
    finally:
        # 只保存可序列化的变量
        for k, v in local_vars.items():
            try:
                session[k] = v
            except Exception:
                pass
```

---

## 阶段十：AndroidManifest 权限

```xml
<!-- pip install 下载包需要网络权限 -->
<uses-permission android:name="android.permission.INTERNET" />

<!-- filesDir 是 App 私有目录，无需额外存储权限 -->
<!-- Android 10+ 分区存储不影响 filesDir -->
```

---

## 整体数据流

```
App 启动
  ↓
PythonEnvManager.ensureReady()
  ├── 检查版本文件 → 已存在 → EnvState.Ready
  └── 不存在 → 解压 assets zip → chmod → 验证 → EnvState.Ready
  ↓
UI 解锁 REPL 输入框

用户输入：import numpy; print(numpy.__version__)
  ↓
REPLViewModel.execute(code)
  ↓
PythonExecutor.executeStreaming(code)
  ↓
ProcessBuilder([pythonBin, "-u", "session_wrapper.py", sessionFile, code])
  + 环境变量：PYTHONPATH 包含 sitePackages
  ↓
Flow<String> 实时读取 stdout → UI 追加输出

用户输入：pip install requests
  ↓
REPLViewModel 检测到 pip install 命令
  ↓
PipInstaller.install("requests")
  ↓
ProcessBuilder([pythonBin, "-m", "pip", "install", "requests", "--target", sitePackages])
  ↓
Flow<String> 实时读取安装日志 → UI 显示
  ↓
exitCode == 0 → InstallState.Success → 后续 import requests 可用
```

---

## 主要风险点

### 1. p4a 构建失败

p4a 依赖链复杂，构建失败是常见问题。

**排查方式：**
- 查看 `~/.local/share/python-for-android/build/` 下的构建日志
- 常见原因：NDK 版本不匹配（推荐 r25b）、缺少系统依赖、网络问题
- 使用 `p4a recommendations` 命令检查环境

### 2. C 扩展包运行时安装

运行时 `pip install` 含 C 扩展的包（numpy、cryptography 等）会失败，因为 Android 上没有编译器。

**解决方案：**
- 在 p4a 构建阶段通过 `--requirements` 编译进发行版（最可靠）
- 运行时只安装纯 Python 包
- 对于必须运行时安装的 C 扩展包，使用 Chaquopy 的 Android wheel 仓库

### 3. APK 体积

p4a 构建的 Python 发行版（含 stdlib）约 25~45MB（zip 压缩后），两个 ABI 合计约 50~90MB。

**解决方案：**
- 裁剪 stdlib（删除 test、tkinter、idlelib 等）
- 使用 Android App Bundle（AAB）按 ABI 分发
- 将 zip 改为首次启动从服务器下载

### 4. 首次启动解压耗时

解压 40MB zip 在低端机上约需 5~15 秒。

**解决方案：**
- 在 Splash Screen 期间异步解压
- `StateFlow<EnvState.Extracting(progress)>` 驱动进度条 UI
- 解压完成前 REPL 输入框显示 "正在初始化 Python 环境..."

### 5. 会话状态序列化限制

`shelve` / `pickle` 无法序列化所有 Python 对象（如 lambda、文件句柄、自定义类实例）。

**解决方案：**
- 捕获序列化异常，跳过不可序列化的变量
- 在 UI 上提示用户哪些变量未被保留
- 对于需要持久化的复杂对象，引导用户使用 `json` 或 `pickle` 手动保存

### 6. SELinux 执行权限

从 `filesDir` 执行二进制在 Android 10+ 通常是允许的，但部分厂商 ROM 可能有额外限制。

**验证方式：**
- 遇到 `Permission denied` 时，运行 `adb logcat | grep avc` 查看 SELinux 拒绝日志
- 确保 Python binary 来自 APK assets（非网络下载），这是 SELinux 允许执行的前提

---

## 参考资源

- [python-for-android 官方文档](https://python-for-android.readthedocs.io/)
- [p4a GitHub 仓库](https://github.com/kivy/python-for-android)
- [p4a 支持的 Recipes 列表](https://github.com/kivy/python-for-android/tree/develop/pythonforandroid/recipes)
- [Android NDK 下载页](https://developer.android.com/ndk/downloads)
- [pip install --target 文档](https://pip.pypa.io/en/stable/cli/pip_install/#cmdoption-t)
- [shelve — Python 对象持久化](https://docs.python.org/3/library/shelve.html)
