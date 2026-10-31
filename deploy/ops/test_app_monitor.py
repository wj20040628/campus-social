#!/usr/bin/env python3
"""app-monitor.py 的规则测试：日志窗口解析、5xx 阈值、健康/重启状态机。"""

import sys
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import app_monitor  # noqa: E402


class NginxWindowTest(unittest.TestCase):
    def test_window_parsing_excludes_old_and_healthz(self):
        now = datetime(2026, 10, 31, 10, 0, 0, tzinfo=timezone(timedelta(hours=8)))
        recent = (now - timedelta(minutes=1)).strftime("%d/%b/%Y:%H:%M:%S %z")
        old = (now - timedelta(minutes=10)).strftime("%d/%b/%Y:%H:%M:%S %z")
        lines = [
            f'1.1.1.1 - - [{recent}] "GET /post/feed HTTP/1.1" 200 100',
            f'1.1.1.1 - - [{recent}] "GET /api/post/1 HTTP/1.1" 500 100',
            f'1.1.1.1 - - [{recent}] "GET /api/upload HTTP/1.1" 502 100',
            f'1.1.1.1 - - [{recent}] "GET /healthz HTTP/1.1" 500 100',
            f'1.1.1.1 - - [{old}] "GET /api/post/2 HTTP/1.1" 500 100',
            "not a log line",
        ]
        total, five_xx = app_monitor.parse_nginx_window(lines, now)
        self.assertEqual(3, total)
        self.assertEqual(2, five_xx)

    def test_threshold_absolute_ratio_and_minimum(self):
        self.assertTrue(app_monitor.five_xx_breached(100, 5))
        self.assertTrue(app_monitor.five_xx_breached(19, 5))
        self.assertFalse(app_monitor.five_xx_breached(19, 4))
        self.assertFalse(app_monitor.five_xx_breached(100, 4))
        self.assertTrue(app_monitor.five_xx_breached(20, 1))
        self.assertFalse(app_monitor.five_xx_breached(20, 0))


class HealthStateTest(unittest.TestCase):
    def test_two_failures_then_recovery(self):
        state = {}
        with mock.patch.object(
            app_monitor, "check_health", side_effect=[(False, "timeout"), (False, "timeout"), (True, "ok")]
        ), mock.patch.object(app_monitor, "send_webhook") as webhook:
            app_monitor.handle_health(state, dry_run=True)
            self.assertFalse(state["health"]["alerted"])
            app_monitor.handle_health(state, dry_run=True)
            self.assertTrue(state["health"]["alerted"])
            self.assertEqual(1, webhook.call_count)
            app_monitor.handle_health(state, dry_run=True)
            self.assertFalse(state["health"]["alerted"])
            self.assertEqual(2, webhook.call_count)
            self.assertIn("已恢复", webhook.call_args_list[1].args[0])


class SystemdStateTest(unittest.TestCase):
    def test_restart_and_failure_are_reported_separately(self):
        state = {}
        first = (
            True,
            {
                "ActiveEnterTimestamp": "2026-10-31 09:00:00 CST",
                "NRestarts": "0",
                "SubState": "running",
                "ExecMainStatus": "0",
            },
            "",
        )
        second = (
            True,
            {
                "ActiveEnterTimestamp": "2026-10-31 10:00:00 CST",
                "NRestarts": "1",
                "SubState": "running",
                "ExecMainStatus": "0",
            },
            "",
        )
        failed = (
            True,
            {
                "ActiveEnterTimestamp": "2026-10-31 10:00:00 CST",
                "NRestarts": "1",
                "SubState": "failed",
                "ExecMainStatus": "1",
            },
            "",
        )
        with mock.patch.object(
            app_monitor, "check_systemd", side_effect=[first, second, failed]
        ), mock.patch.object(app_monitor, "send_webhook") as webhook:
            app_monitor.handle_systemd(state, dry_run=True)
            self.assertEqual(0, webhook.call_count)
            app_monitor.handle_systemd(state, dry_run=True)
            self.assertEqual(1, webhook.call_count)
            self.assertIn("发生重启", webhook.call_args_list[0].args[0])
            app_monitor.handle_systemd(state, dry_run=True)
            self.assertEqual(2, webhook.call_count)
            self.assertIn("状态=failed", webhook.call_args_list[1].args[0])


if __name__ == "__main__":
    unittest.main()
