# CHANGES

## Bug Fix: pip install 在 subprocess 中 SIGSEGV（exit code 139）

### 问题现象

执行 `pip install <package>` 时，REPLViewModel 日志输出 `__EXIT__:139`，
crash_dump64 显示进程在 `libpython3.11.so` 内部崩溃。

```
crash_dump64: libpython3exec.so -S pip_runner.py install numpy ...
REPLViewModel: __EXIT__:139
```

exit code 139 = 128 + 11 = **SIGSEGV（段错误）**。

---

### 根本原因分析

#### 第一层：为什么用了 `-S` 还是崩溃？

`-S` 标志的作用是跳过 `site.py` 和 `sitecustomize.py`。
p4a 的 `sitecustomize.py` 会执行 `import android`，`android` 包内部会加载 `jnius`，
`jnius` 在初始化时调用 `WebView_AndroidGetJNIEnv`，而 subprocess 中没有 JVM，
因此 SIGSEGV。这是 `-S` 原本要阻断的路径。

但 `pip_runner.py` 在跳过 `site.py` 后，为了让 pip 能找到自己，
手动把 `PYTHONPATH` 里的所有路径加回了 `sys.path`：

```python
_pythonpath = os.environ.get('PYTHONPATH', '')
for _p in _pythonpath.split(':'):
    if _p and _p not in sys.path:
        sys.path.append(_p)
```

`PYTHONPATH` 的值为：

```
stdlib.zip : modules : site-packages
```

这导致 `site-packages` 被加回了 `sys.path`，`-S` 的防护完全失效。

#### 第二层：谁触发了 `import android`？

crash_dump64 的完整调用栈（关键帧）：

```
#180 Py_BytesMain                     <- pip_runner.py 进程入口
#176 _PyRun_SimpleFileObject          <- 开始执行 pip_runner.py
...
#68  PyImport_ImportModuleLevelObject  <- pip 内部某个 import 语句
...
#30  android/_android.so              <- import android 触发 C 扩展初始化
#01  jnius/jnius.so                   <- android 包内部加载 jnius
#00  WebView_AndroidGetJNIEnv+26      <- SIGSEGV：subprocess 无 JVM
```

crash_dump64 的 NOTE 段也明确列出了已加载的问题库：

```
NOTE: Unreadable libraries:
  .../site-packages/android/_android.so
  .../site-packages/jnius/jnius.so
```

pip 在 import 自身模块时，通过某条间接路径触发了 `import android`，
进而加载了 `jnius`，最终崩溃。

#### 第三层：为什么不能直接把 site-packages 从 PYTHONPATH 移除？

pip 本身就安装在 site-packages 里。
移除 site-packages 后，`from pip._internal.cli.main import main` 会报
`No module named pip`，pip 根本无法启动。

---

### 尝试过的方案

#### 方案 A（失败）：从 PYTHONPATH 移除 site-packages

在 `PythonEnvConfig.kt` 新增 `buildPipEnvVars()`，
其中 `PYTHONPATH` 只含 `stdlib.zip:modules`，不含 `site-packages`。
`PipInstaller` 使用这个新方法。

**结果**：pip 找不到自己，报 `No module named pip`。
**原因**：pip 包本身在 site-packages 里，移除后无法 import。

#### 方案 B（最终方案）：在进程级别屏蔽危险模块

在 `pip_runner.py` 的 `sys.path` 恢复之后、任何真实 import 发生之前，
用空的 `ModuleType` 对象预先占据 `sys.modules['android']` 和 `sys.modules['jnius']`：

```python
import types as _types
for _blocked in ('android', 'jnius'):
    sys.modules[_blocked] = _types.ModuleType(_blocked)
del _types, _blocked
```

Python 的 import 机制在加载任何模块前都会先查 `sys.modules` 缓存。
预先注册空模块后，后续任何 `import android` 或 `import jnius` 都直接命中缓存，
返回空模块对象，真实的 `.so` 文件永远不会被执行。

**优势**：
- pip 仍然可以找到自己（site-packages 还在 sys.path 里）
- `android` 和 `jnius` 的 `.so` 永远不会在 subprocess 中加载
- 将来 site-packages 里装了其他间接依赖 jnius 的包，同样受到保护

---

### 附带问题：`Not a directory: stdlib.zip/urllib3`

修复 SIGSEGV 后，pip install 能运行，但安装阶段报错：

```
ERROR: Could not install packages due to an OSError:
[Errno 20] Not a directory: '.../stdlib.zip/urllib3'
```

**原因**：`pip_runner.py` 的 `_safe_paths` 函数用 `PYTHONPATH` 的第一个条目
（即 `stdlib.zip`）作为 `purelib` 和 `platlib` 返回给 pip。
pip 在某些路径计算中会参考 `purelib`，导致它尝试往 `stdlib.zip/urllib3` 写文件，
但 `stdlib.zip` 是 zip 文件而非目录，所以报 `Not a directory`。

**修复**：区分 `stdlib` 和 `site-packages` 的路径：

```python
def _safe_paths(*a, **k):
    pp = os.environ.get('PYTHONPATH', '').split(':')
    stdlib = next((p for p in pp if p), '')
    # site-packages 是 PYTHONPATH 的最后一个非空条目
    site_pkgs = next((p for p in reversed(pp) if p), stdlib)
    return {
        'stdlib': stdlib, 'platstdlib': stdlib,
        'purelib': site_pkgs, 'platlib': site_pkgs,  # <- 指向真实目录
        'include': '', 'scripts': '', 'data': '',
    }
```

`PYTHONPATH = stdlib.zip:modules:site-packages`，所以：
- `stdlib` = 第一个条目 = `stdlib.zip`
- `site_pkgs` = 最后一个条目 = `site-packages`（真实目录）

---

### 最终修改文件

只修改了 `app/src/main/assets/pip_runner.py`，Kotlin 代码无需改动。

**修改 1**：新增 `sys.modules` 屏蔽块（解决 SIGSEGV）

```python
import types as _types
for _blocked in ('android', 'jnius'):
    sys.modules[_blocked] = _types.ModuleType(_blocked)
del _types, _blocked
```

**修改 2**：修正 `_safe_paths` 的 `purelib`/`platlib`（解决 Not a directory）

```python
stdlib = next((p for p in pp if p), '')
site_pkgs = next((p for p in reversed(pp) if p), stdlib)
return {
    'stdlib': stdlib, 'platstdlib': stdlib,
    'purelib': site_pkgs, 'platlib': site_pkgs,
    ...
}
```

---

### 涉及的关键知识点

**`-S` 标志**
Python 启动参数，跳过 `site.py` 的执行。`site.py` 负责把 site-packages 加入
`sys.path`，并执行 `sitecustomize.py`。跳过后 `sys.path` 只含内置模块路径。

**`sys.modules` 缓存**
Python import 机制的核心数据结构，是一个 `dict`，key 为模块名，value 为模块对象。
每次 `import foo` 时，解释器先查 `sys.modules`，命中则直接返回，不再执行任何文件。
在进程启动早期预先写入假模块，可以完全阻止真实模块被加载。

**`sysconfig` 模块**
Python 标准库，提供安装路径信息（stdlib、purelib、platlib 等）。
pip 用它来确定包的安装位置。p4a 构建的 Python 中，`sysconfig` 的某些函数
会触发 SIGSEGV，因此需要在 pip 运行前打补丁替换为安全实现。

**`purelib` vs `platlib`**
`sysconfig` 返回的路径键：
- `purelib`：纯 Python 包的安装目录（通常就是 site-packages）
- `platlib`：平台相关包（含 C 扩展）的安装目录（通常也是 site-packages）

pip 在没有 `--target` 覆盖某些内部路径计算时，会参考这两个值。

**p4a（python-for-android）**
将 CPython 打包为 Android 可用形式的工具链。产物包括：
- `libpython3exec.so`：Python 可执行文件（以 `.so` 形式绕过 Android W^X 限制）
- `libpython3.11.so`：Python 共享库
- `stdlib.zip`：标准库压缩包
- `modules/`：C 扩展模块目录
- `sitecustomize.py`：p4a 自定义的站点初始化脚本，会 import android/jnius

**Android W^X 限制**
Android 不允许从 `/data` 分区直接执行二进制文件（Write XOR Execute）。
将 Python 可执行文件命名为 `.so` 并放入 `nativeLibraryDir`，
是绕过此限制的标准做法，因为系统允许从该目录加载和执行 `.so`。

---

## 背景知识：两种 Python for Android

### 一、Kivy 的 python-for-android（p4a）

**项目地址**：https://github.com/kivy/python-for-android
**维护方**：Kivy 社区（开源，非官方）
**定位**：面向开发者的构建工具，把 Python 应用打包成 Android APK/AAB

#### 构建原理

p4a 的核心概念是 **Recipe（配方）** 和 **Bootstrap（引导层）**。

**Recipe**
每个 Recipe 是一个描述"如何为 Android 交叉编译某个库"的脚本。
纯 Python 包不需要 Recipe，直接用 pip 安装即可；
含 C/C++/Cython 扩展的包（如 numpy、cryptography）必须有对应 Recipe，
因为它们需要针对 Android ABI（arm64-v8a、x86_64 等）重新编译。

**Bootstrap**
Bootstrap 是 Android 项目的模板层，决定 Python 如何被宿主 App 启动：
- `SDL2`：默认，用于 Kivy 图形应用，通过 SDL2 创建 OpenGL 窗口
- `WebView`：用于 Web 应用，Python 作为后端，前端用 WebView 渲染
- `service_only`：无界面后台服务
- `Qt`：用于 PySide6 应用

**构建流程**

```
p4a apk --requirements=kivy,numpy --bootstrap=sdl2 --arch=arm64-v8a
  |
  +-- 1. 交叉编译 Python 解释器（针对目标 ABI）
  +-- 2. 执行各依赖的 Recipe（编译 C 扩展）
  +-- 3. 打包 stdlib -> stdlib.zip
  +-- 4. 将 C 扩展放入 _python_bundle/modules/
  +-- 5. 生成 Bootstrap 的 Android 项目（Java/Kotlin 壳）
  +-- 6. 用 Gradle 打包成 APK
```

#### APK 内部产物结构

```
APK/
├── lib/
│   └── arm64-v8a/
│       ├── libpython3exec.so      <- Python 可执行文件（伪装成 .so 绕过 W^X）
│       ├── libpython3.11.so       <- Python 共享库
│       └── libmain.so             <- Bootstrap 的 JNI 入口
└── assets/
    └── private.mp3                <- 加密打包的 Python 资源（实为 zip）
        └── _python_bundle/
            ├── stdlib.zip         <- Python 标准库
            ├── modules/           <- C 扩展模块（.so 文件）
            ├── site-packages/     <- 第三方包
            └── sitecustomize.py   <- p4a 自定义初始化脚本
```

#### sitecustomize.py 的作用

p4a 的 `sitecustomize.py` 在 Python 启动时自动执行，负责：
1. 设置 `ANDROID_PRIVATE`、`ANDROID_UNPACK` 等环境变量
2. 执行 `import android`，初始化 Android 平台适配层
3. `android` 包内部执行 `import jnius`，建立 Python-Java 桥接

这就是为什么在 subprocess 中（无 JVM）运行 Python 时，
`sitecustomize.py` 会导致 SIGSEGV——jnius 找不到 JVM 就崩溃。

#### jnius 和 android 包

- **jnius（Pyjnius）**：通过 JNI 动态包装 Java 类，让 Python 代码可以直接调用任意 Android/Java API。
  例如：`from jnius import autoclass; Intent = autoclass('android.content.Intent')`
- **android 包**：p4a 提供的高层封装，包装了常用 Android API（权限、存储路径、Activity 生命周期等），
  内部依赖 jnius。默认随所有 p4a 构建一起安装到 site-packages。

#### 使用方式

```bash
# 安装
pip install python-for-android

# 构建 APK（需要 Android SDK/NDK 环境）
p4a apk \
  --private /path/to/your/app \
  --package org.example.myapp \
  --name "My App" \
  --version 1.0 \
  --bootstrap sdl2 \
  --requirements python3,kivy \
  --arch arm64-v8a

# 也可通过 Buildozer（p4a 的高层封装）使用
pip install buildozer
buildozer android debug
```

---

### 二、CPython 官方 Android 支持（PEP 738）

**PEP 地址**：https://peps.python.org/pep-0738/
**引入版本**：Python 3.13（Tier 3 支持级别）
**维护方**：CPython 核心开发团队（官方）
**定位**：在 CPython 主线中原生支持 Android 作为构建目标，
不是独立工具，而是 CPython 本身的一部分

#### 与 p4a 的本质区别

p4a 是一个**打包工具**，它调用 CPython 源码并为 Android 交叉编译；
官方支持是把 Android 作为 CPython 的**一级构建目标**，
就像 Linux/macOS/Windows 一样，可以直接从 CPython 源码构建出 Android 版本。

#### 构建原理

官方 Android 支持采用**嵌入式模式（embedded mode）**：
Python 不作为独立可执行文件运行，而是编译为 `libpython3.x.so`，
由宿主 Android 应用通过 JNI 调用 Python 嵌入 API 来启动解释器。

```
CPython 源码
  |
  +-- ./configure --host=aarch64-linux-android ...
  +-- make
  |
  +-- 产物：
      ├── libpython3.13.so        <- Python 共享库（嵌入到 Android app）
      ├── python3.13/             <- 标准库（目录形式，非 zip）
      └── include/                <- C 头文件
```

#### 产物结构

官方构建产物放入 Android 项目的 JNI 和 assets 目录：

```
Android 项目/
├── app/src/main/jniLibs/
│   └── arm64-v8a/
│       └── libpython3.13.so     <- Python 共享库
└── app/src/main/assets/
    └── python/
        ├── lib/
        │   └── python3.13/      <- 标准库（目录，非 zip）
        └── site-packages/       <- 第三方包
```

#### 使用方式

官方支持目前主要面向**框架开发者**（如 BeeWare、Kivy），
而非直接面向应用开发者。使用步骤：

```bash
# 1. 从 CPython 源码构建 Android 版本
git clone https://github.com/python/cpython
cd cpython
./Android/android.py build --arch arm64-v8a

# 2. 在 Android 项目中嵌入（C/JNI 代码）
# 初始化 Python
Py_Initialize();
PyRun_SimpleString("print('Hello from Python!')");
Py_Finalize();
```

BeeWare 的 Briefcase 工具已经基于官方支持构建了更高层的工作流：

```bash
pip install briefcase
briefcase new          # 创建项目
briefcase run android  # 在 Android 设备上运行
briefcase build android --target android:33  # 构建 APK
```

---

### 三、两者对比

| 维度 | Kivy p4a | CPython 官方（PEP 738） |
|------|----------|------------------------|
| 维护方 | Kivy 社区 | CPython 核心团队 |
| 引入时间 | ~2012 年 | Python 3.13（2024） |
| 支持级别 | 社区维护 | Tier 3（官方） |
| Python 版本 | 3.8+ | 3.13+ |
| 最低 API | 21（可配置） | 21 |
| 支持 ABI | arm64-v8a, x86_64, armeabi-v7a, x86 | arm64-v8a, x86_64（仅 64 位） |
| stdlib 打包 | `stdlib.zip`（压缩） | 目录形式 |
| 可执行文件 | `libpython3exec.so`（伪装 .so） | 无独立可执行文件，纯嵌入模式 |
| Java 桥接 | jnius（内置） | 无（需自行实现或用第三方库） |
| sitecustomize.py | 有，自动 import android/jnius | 无 |
| 面向对象 | 应用开发者（通过 Buildozer） | 框架开发者（BeeWare 等） |
| 直接使用难度 | 中（有 Buildozer 封装） | 高（需要手写 JNI 胶水代码） |

#### 关键差异说明

**stdlib 打包方式**
p4a 将标准库压缩为 `stdlib.zip` 以减小 APK 体积，Python 通过 zipimport 直接从 zip 加载模块。
官方支持保留目录结构，更接近桌面 Python 的行为，但体积更大。

**可执行文件 vs 嵌入模式**
p4a 通过将 Python 可执行文件伪装成 `.so`（`libpython3exec.so`）放入 `nativeLibraryDir`
来绕过 Android 的 W^X 限制，使其可以像普通进程一样被 `ProcessBuilder` 启动。
官方支持不提供可执行文件，Python 只能作为库被宿主 App 通过 JNI 调用，
无法用 `ProcessBuilder` 直接启动一个 Python 子进程。

**jnius 的存在**
p4a 内置 jnius，Python 代码可以直接调用任意 Java/Android API，
这是 Kivy 应用能访问 Android 系统功能的基础。
官方支持不包含 Java 桥接层，需要开发者自行通过 JNI 或第三方库实现。

**本项目使用 p4a 的原因**
本项目需要通过 `ProcessBuilder` 启动 Python 子进程来执行用户代码，
这依赖 p4a 的 `libpython3exec.so` 可执行文件方案。
官方的纯嵌入模式无法满足这个需求。

---

**参考资料**
- [kivy/python-for-android GitHub](https://github.com/kivy/python-for-android)
- [p4a 官方文档](https://python-for-android.readthedocs.io/en/latest/)
- [PEP 738 – Adding Android as a supported platform](https://peps.python.org/pep-0738/)
- [CPython 官方 Android 使用文档](https://docs.python.org/3/using/android.html)
