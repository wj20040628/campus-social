#!/usr/bin/env python3
"""友声应用层巡检：Nginx 5xx、campus.service 重启、公开 healthz。

由 campus-monitor.timer 每 5 分钟执行。告警复用 /opt/campus/.env 的
ALERT_WEBHOOK_URL（企业微信/钉钉群机器人），状态保存在 /var/lib/campus-monitor。
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timedelta
from pathlib import Path

ENV_FILE = Path(os.environ.get("MONITOR_ENV_FILE", "/opt/campus/.env"))
STATE_DIR = Path(os.environ.get("MONITOR_STATE_DIR", "/var/lib/campus-monitor"))
STATE_FILE = STATE_DIR / "state.json"
LOCK_FILE = STATE_DIR / "lock"
LOG_FILE = Path(os.environ.get("MONITOR_LOG_FILE", "/var/log/campus-monitor.log"))
NGINX_LOG = Path(os.environ.get("MONITOR_NGINX_LOG", "/var/log/nginx/access.log"))
HEALTH_URL = os.environ.get("MONITOR_HEALTH_URL", "https://yousheng8.cn/healthz")
UNIT = os.environ.get("MONITOR_SYSTEMD_UNIT", "campus.service")
WINDOW_SECONDS = int(os.environ.get("MONITOR_WINDOW_SECONDS", "300"))
FIVE_XX_ABS = int(os.environ.get("MONITOR_5XX_ABS", "5"))
FIVE_XX_RATIO = float(os.environ.get("MONITOR_5XX_RATIO", "0.05"))
MIN_REQUESTS = int(os.environ.get("MONITOR_MIN_REQUESTS", "20"))
HEALTH_FAIL_THRESHOLD = int(os.environ.get("MONITOR_HEALTH_FAILS", "2"))
COOLDOWN_SECONDS = int(os.environ.get("MONITOR_COOLDOWN_SECONDS", "3600"))
SKIP_SYSTEMD = os.environ.get("MONITOR_SKIP_SYSTEMD") == "1"
TAIL_BYTES = int(os.environ.get("MONITOR_TAIL_BYTES", str(2 * 1024 * 1024)))

NGINX_RE = re.compile(r'^\S+ \S+ \S+ \[([^\]]+)\] "([^"]*)" (\d{3}) ')


def now_local() -> datetime:
    return datetime.now().astimezone()


def now_text() -> str:
    return now_local().strftime("%F %T %Z")


def log(message: str) -> None:
    line = f"{now_text()} | {message}"
    print(line)
    try:
        LOG_FILE.parent.mkdir(parents=True, exist_ok=True)
        with LOG_FILE.open("a", encoding="utf-8") as fh:
            fh.write(line + "\n")
        os.chmod(LOG_FILE, 0o600)
    except OSError as exc:
        print(f"日志写入失败: {exc}", file=sys.stderr)


def load_env(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    try:
        for raw in path.read_text(encoding="utf-8").splitlines():
            line = raw.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            if line.startswith("export "):
                line = line[7:].lstrip()
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip().strip('"').strip("'")
    except OSError as exc:
        log(f"读取 {path} 失败: {exc}")
    return values


def load_state() -> dict:
    try:
        return json.loads(STATE_FILE.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}


def save_state(state: dict) -> None:
    STATE_DIR.mkdir(parents=True, exist_ok=True)
    os.chmod(STATE_DIR, 0o700)
    tmp = STATE_FILE.with_suffix(".tmp")
    tmp.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")
    os.chmod(tmp, 0o600)
    os.replace(tmp, STATE_FILE)


def acquire_lock():
    import fcntl

    STATE_DIR.mkdir(parents=True, exist_ok=True)
    os.chmod(STATE_DIR, 0o700)
    handle = LOCK_FILE.open("w")
    try:
        fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        handle.close()
        return None
    return handle


def send_webhook(text: str, dry_run: bool) -> None:
    if dry_run:
        log(f"[dry-run] {text}")
        return
    env = load_env(ENV_FILE)
    url = os.environ.get("ALERT_WEBHOOK_URL") or env.get("ALERT_WEBHOOK_URL", "")
    if not url:
        log(f"未配置 ALERT_WEBHOOK_URL，告警未发送: {text}")
        return
    payload = json.dumps(
        {"msgtype": "text", "text": {"content": text}}, ensure_ascii=False
    ).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=payload,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            body = response.read(512).decode("utf-8", "replace")
            if response.status >= 300:
                log(f"Webhook HTTP {response.status}: {body}")
                return
            try:
                data = json.loads(body)
            except json.JSONDecodeError:
                return
            errcode = data.get("errcode")
            if errcode not in (None, 0):
                log(f"Webhook 返回 errcode={errcode}: {body}")
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        log(f"Webhook 发送失败: {exc}")


def alert_text(kind: str, message: str, recovery: bool = False) -> str:
    if recovery:
        return f"友声应用监控 | {kind}已恢复 | {message}"
    return f"友声应用监控告警 | {kind} | {message} | {now_text()}"


def tail_lines(path: Path) -> list[str]:
    lines: list[str] = []
    candidates = [path, Path(str(path) + ".1")]
    for candidate in candidates:
        if not candidate.exists():
            continue
        try:
            with candidate.open("rb") as fh:
                fh.seek(0, os.SEEK_END)
                size = fh.tell()
                fh.seek(max(0, size - TAIL_BYTES))
                data = fh.read()
            lines.extend(data.decode("utf-8", "replace").splitlines())
        except OSError as exc:
            log(f"读取 Nginx 日志 {candidate} 失败: {exc}")
    return lines


def parse_nginx_window(lines: list[str], now: datetime) -> tuple[int, int]:
    cutoff = now - timedelta(seconds=WINDOW_SECONDS + 60)
    upper = now + timedelta(seconds=60)
    total = 0
    five_xx = 0
    for line in lines:
        match = NGINX_RE.match(line)
        if not match:
            continue
        try:
            timestamp = datetime.strptime(match.group(1), "%d/%b/%Y:%H:%M:%S %z")
        except ValueError:
            continue
        if timestamp < cutoff or timestamp > upper:
            continue
        request = match.group(2)
        parts = request.split()
        request_path = parts[1] if len(parts) >= 2 else ""
        if request_path.startswith("/healthz"):
            continue
        total += 1
        status = int(match.group(3))
        if 500 <= status <= 599:
            five_xx += 1
    return total, five_xx


def five_xx_breached(total: int, five_xx: int) -> bool:
    if five_xx >= FIVE_XX_ABS:
        return True
    if total >= MIN_REQUESTS and total > 0:
        return five_xx / total >= FIVE_XX_RATIO
    return False


def check_health() -> tuple[bool, str]:
    request = urllib.request.Request(
        HEALTH_URL, headers={"User-Agent": "campus-monitor/1.0"}
    )
    try:
        with urllib.request.urlopen(request, timeout=5) as response:
            body = response.read(4096)
            if response.status != 200:
                return False, f"HTTP {response.status}"
            try:
                data = json.loads(body.decode("utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError):
                return False, "响应不是合法 JSON"
            status = str(data.get("status", ""))
            return status.upper() == "UP", f"status={status or 'missing'}"
    except urllib.error.HTTPError as exc:
        return False, f"HTTP {exc.code}"
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        return False, f"连接失败: {exc}"


def check_systemd() -> tuple[bool, dict[str, str], str]:
    try:
        result = subprocess.run(
            [
                "systemctl",
                "show",
                UNIT,
                "-p",
                "ActiveEnterTimestamp",
                "-p",
                "NRestarts",
                "-p",
                "SubState",
                "-p",
                "ExecMainStatus",
            ],
            capture_output=True,
            text=True,
            timeout=10,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        return False, {}, f"执行 systemctl 失败: {exc}"
    if result.returncode != 0:
        return False, {}, f"systemctl 返回 {result.returncode}: {result.stderr.strip()}"
    props: dict[str, str] = {}
    for line in result.stdout.splitlines():
        if "=" in line:
            key, value = line.split("=", 1)
            props[key.strip()] = value.strip()
    if not props:
        return False, {}, "systemctl 未返回状态属性"
    return True, props, ""


def handle_nginx(state: dict, dry_run: bool) -> None:
    section = state.setdefault("nginx", {"alerted": False, "last_alert_ts": 0})
    try:
        lines = tail_lines(NGINX_LOG)
    except OSError as exc:
        log(f"读取 Nginx access.log 失败: {exc}")
        return
    if not lines:
        log(f"Nginx access.log 为空或不存在: {NGINX_LOG}")
        return
    total, five_xx = parse_nginx_window(lines, now_local())
    breached = five_xx_breached(total, five_xx)
    now_ts = int(time.time())
    ratio = (five_xx * 100.0 / total) if total else 0.0
    if breached:
        if now_ts - int(section.get("last_alert_ts", 0)) >= COOLDOWN_SECONDS:
            text = alert_text(
                "Nginx 5xx",
                f"最近 {WINDOW_SECONDS // 60} 分钟 5xx={five_xx}/{total}（{ratio:.1f}%），"
                f"阈值={FIVE_XX_ABS} 次或 {FIVE_XX_RATIO * 100:.0f}%",
            )
            send_webhook(text, dry_run)
            section["last_alert_ts"] = now_ts
        section["alerted"] = True
    else:
        if section.get("alerted") and five_xx == 0:
            send_webhook(
                alert_text(
                    "Nginx 5xx",
                    f"最近 {WINDOW_SECONDS // 60} 分钟无 5xx（总请求 {total}）",
                    recovery=True,
                ),
                dry_run,
            )
            section["alerted"] = False
    section["last_counts"] = {
        "total": total,
        "five_xx": five_xx,
        "window_seconds": WINDOW_SECONDS,
        "at": now_text(),
    }


def handle_health(state: dict, dry_run: bool) -> None:
    section = state.setdefault("health", {"failures": 0, "alerted": False})
    ok, detail = check_health()
    if ok:
        if section.get("alerted"):
            send_webhook(
                alert_text("healthz", f"{HEALTH_URL} 已恢复（{detail}）", recovery=True),
                dry_run,
            )
            section["alerted"] = False
        section["failures"] = 0
        section["last_ok"] = now_text()
        section["last_error"] = ""
        return
    failures = int(section.get("failures", 0)) + 1
    section["failures"] = failures
    section["last_error"] = detail
    if failures >= HEALTH_FAIL_THRESHOLD and not section.get("alerted"):
        send_webhook(
            alert_text(
                "healthz",
                f"{HEALTH_URL} 连续 {failures} 次失败（{detail}）",
            ),
            dry_run,
        )
        section["alerted"] = True


def handle_systemd(state: dict, dry_run: bool) -> None:
    if SKIP_SYSTEMD:
        return
    section = state.setdefault("systemd", {"active_enter": "", "n_restarts": 0})
    ok, props, error = check_systemd()
    now_ts = int(time.time())
    if not ok:
        if now_ts - int(section.get("last_alert_ts", 0)) >= COOLDOWN_SECONDS:
            send_webhook(alert_text("systemd", error), dry_run)
            section["last_alert_ts"] = now_ts
        return
    active_enter = props.get("ActiveEnterTimestamp", "")
    try:
        n_restarts = int(props.get("NRestarts", "0") or 0)
    except ValueError:
        n_restarts = 0
    substate = props.get("SubState", "")
    exec_status = props.get("ExecMainStatus", "")
    previous_active = section.get("active_enter", "")
    previous_restarts = int(section.get("n_restarts", 0) or 0)
    restarted = bool(previous_active and active_enter and active_enter != previous_active)
    restarted = restarted or n_restarts > previous_restarts
    if substate != "running":
        if now_ts - int(section.get("last_failure_alert_ts", 0)) >= COOLDOWN_SECONDS:
            send_webhook(
                alert_text(
                    "systemd",
                    f"{UNIT} 状态={substate or 'unknown'}，ExecMainStatus={exec_status or 'unknown'}",
                ),
                dry_run,
            )
            section["last_failure_alert_ts"] = now_ts
    elif restarted:
        if now_ts - int(section.get("last_alert_ts", 0)) >= COOLDOWN_SECONDS:
            send_webhook(
                alert_text(
                    "systemd",
                    f"{UNIT} 发生重启：{previous_active or '无记录'} -> {active_enter}，"
                    f"NRestarts {previous_restarts} -> {n_restarts}",
                ),
                dry_run,
            )
            section["last_alert_ts"] = now_ts
    section["active_enter"] = active_enter
    section["n_restarts"] = n_restarts
    section["substate"] = substate
    section["exec_main_status"] = exec_status


def run(dry_run: bool) -> int:
    if not dry_run:
        lock = acquire_lock()
        if lock is None:
            log("已有巡检实例在运行，跳过本次")
            return 0
    else:
        lock = None
    try:
        state = load_state()
        handle_nginx(state, dry_run)
        handle_health(state, dry_run)
        handle_systemd(state, dry_run)
        if not dry_run:
            save_state(state)
        return 0
    finally:
        if lock is not None:
            lock.close()


def main() -> int:
    parser = argparse.ArgumentParser(description="友声应用层巡检")
    parser.add_argument(
        "--dry-run", action="store_true", help="不写状态、不发 Webhook，仅打印结果"
    )
    args = parser.parse_args()
    return run(args.dry_run)


if __name__ == "__main__":
    sys.exit(main())
