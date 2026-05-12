"""
session_wrapper.py — REPL 会话状态保持脚本

用法：
    python3 session_wrapper.py <session_file> <code_string>

- 从 shelve 文件加载上次会话变量
- exec 用户代码
- 将可序列化的变量写回 shelve
"""
import shelve
import sys
import traceback

if len(sys.argv) < 3:
    print("Usage: session_wrapper.py <session_file> <code>", file=sys.stderr)
    sys.exit(1)

SESSION_FILE = sys.argv[1]
CODE = sys.argv[2]

with shelve.open(SESSION_FILE) as session:
    local_vars = dict(session)
    try:
        exec(compile(CODE, "<repl>", "exec"), local_vars)
    except SystemExit:
        pass
    except Exception:
        traceback.print_exc()
    finally:
        # 只保存可序列化的变量，跳过内置名称
        for k, v in local_vars.items():
            if k.startswith("__"):
                continue
            try:
                session[k] = v
            except Exception:
                pass
