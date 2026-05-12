"""
pip_runner.py
必须以 python -S pip_runner.py <pip 参数> 方式运行。

-S 标志跳过 site.py，从而跳过 sitecustomize.py。
p4a 的 sitecustomize.py 会 import android → jnius，
jnius 在 subprocess 中调用 WebView_AndroidGetJNIEnv 时因无 JVM 而 SIGSEGV。
"""
import sys
import os

# -S 跳过了 site.py，sys.path 里只有内置模块。
# 从 PYTHONPATH 环境变量手动恢复路径（stdlib.zip / modules / site-packages）。
_pythonpath = os.environ.get('PYTHONPATH', '')
for _p in _pythonpath.split(':'):
    if _p and _p not in sys.path:
        sys.path.append(_p)

# 在 sys.path 恢复后、任何真实 import 发生前，用空模块占位屏蔽 android 和 jnius。
# 这两个包的 C 扩展在无 JVM 的 subprocess 中初始化时会调用
# WebView_AndroidGetJNIEnv 导致 SIGSEGV。用空模块拦截后，
# 无论 pip 的 import 链如何触达它们，都不会加载真实的 .so。
import types as _types
for _blocked in ('android', 'jnius'):
    sys.modules[_blocked] = _types.ModuleType(_blocked)
del _types, _blocked

# 修补 sysconfig，防止 pip._internal.locations._sysconfig 中的 SIGSEGV。
import sysconfig

def _safe_paths(*a, **k):
    pp = os.environ.get('PYTHONPATH', '').split(':')
    stdlib = next((p for p in pp if p), '')
    # site-packages 是 PYTHONPATH 的最后一个非空条目
    site_pkgs = next((p for p in reversed(pp) if p), stdlib)
    return {
        'stdlib': stdlib, 'platstdlib': stdlib,
        'purelib': site_pkgs, 'platlib': site_pkgs,
        'include': '', 'scripts': '', 'data': '',
    }

sysconfig.get_paths          = _safe_paths
sysconfig.get_path           = lambda name, *a, **k: _safe_paths().get(name, '')
sysconfig.get_default_scheme = lambda: 'posix_prefix'
sysconfig.get_scheme_names   = lambda: ('posix_prefix', 'posix_home', 'nt')

# 运行 pip
from pip._internal.cli.main import main
sys.exit(main(sys.argv[1:]))
