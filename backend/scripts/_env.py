# -*- coding: utf-8 -*-
"""读本机 .env 的小助手(绝不入库、绝不把值写进源码)。

背景:凭据只进根目录 .env(已 gitignore),脚本一律引用而不是写死。
此前 t_e2e.py / verify-resubmit.py 把 MySQL root 密码明文写进源码,
与 README「源码 ${ENV:} 引用、默认留空」的原则相悖,已改为从这里读。

优先级:进程环境变量 > 根目录 .env > 失败(抛错,不静默用空密码)。
"""
import os, sys

# 根目录 .env 的路径。脚本在 backend/scripts/ 下,根目录 = 上两级。
_ROOT_ENV = os.path.abspath(
    os.path.join(os.path.dirname(__file__), "..", "..", ".env")
)


def _load_dotenv(path):
    """极简 .env 解析:每行 KEY=VALUE,跳过注释与空行。返回 dict,不覆盖已存在的进程变量。"""
    out = {}
    if not os.path.exists(path):
        return out
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            s = line.strip()
            if not s or s.startswith("#") or "=" not in s:
                continue
            k, v = s.split("=", 1)
            k = k.strip()
            # 去掉可能的引号
            v = v.strip().strip('"').strip("'")
            out[k] = v
    return out


_dotenv = _load_dotenv(_ROOT_ENV)


def get(key):
    """取 .env 值:进程环境变量优先,其次 .env,再缺则返回 None。"""
    if key in os.environ:
        return os.environ[key]
    return _dotenv.get(key)


def require(key):
    """取必填值:没有就退出并提示,避免拿空密码连库造成假绿/假红。"""
    v = get(key)
    if not v:
        sys.stderr.write(
            "[_env] 缺少 %s。请在本目录 .env 里配置 %s=..., "
            "或用同名环境变量注入(脚本绝不读源码里的明文)。\n" % (key, key)
        )
        sys.exit(2)
    return v
