#!/usr/bin/env python3
"""校验 release-manifest.json 与仓库、构建产物的对应关系。

发布前运行：
  python scripts/verify-release-manifest.py --jar path/to/campus-social.jar

不带 --jar 时跳过 jar 校验，只校验版本号、前端入口、表数量、迁移文件和 git 来源。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
POM_NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def main() -> int:
    parser = argparse.ArgumentParser(description="校验发布清单")
    parser.add_argument("--manifest", default=str(ROOT / "release-manifest.json"))
    parser.add_argument("--jar", default="", help="后端 jar 路径，提供时校验 MD5 与大小")
    parser.add_argument("--dist", default=str(ROOT / "frontend" / "dist"))
    parser.add_argument("--schema", default=str(ROOT / "sql" / "schema.sql"))
    parser.add_argument("--no-git", action="store_true", help="跳过 git 来源校验")
    args = parser.parse_args()

    errors: list[str] = []

    def ok(message: str) -> None:
        print(f"[通过] {message}")

    def fail(message: str) -> None:
        errors.append(message)
        print(f"[失败] {message}")

    manifest_path = Path(args.manifest)
    try:
        data = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        print(f"[失败] 读取清单失败: {exc}")
        return 1

    version = str(data.get("version", ""))
    if not version:
        fail("清单缺少 version")
    else:
        ok(f"清单版本 {version}")

    try:
        pom_root = ET.parse(ROOT / "backend" / "pom.xml").getroot()
        pom_version = pom_root.findtext("m:version", namespaces=POM_NS)
    except (OSError, ET.ParseError) as exc:
        pom_version = None
        fail(f"读取 backend/pom.xml 失败: {exc}")
    if pom_version is not None:
        if pom_version == version:
            ok(f"后端版本匹配 {pom_version}")
        else:
            fail(f"后端版本不一致: pom={pom_version} manifest={version}")

    try:
        package_version = json.loads(
            (ROOT / "frontend" / "package.json").read_text(encoding="utf-8")
        ).get("version")
    except (OSError, json.JSONDecodeError) as exc:
        package_version = None
        fail(f"读取 frontend/package.json 失败: {exc}")
    if package_version is not None:
        if package_version == version:
            ok(f"前端版本匹配 {package_version}")
        else:
            fail(f"前端版本不一致: package.json={package_version} manifest={version}")

    frontend = data.get("frontend", {})
    entry = frontend.get("entry", "")
    entry_path = Path(args.dist) / entry
    if not entry:
        fail("清单缺少 frontend.entry")
    elif not entry_path.is_file():
        fail(f"前端入口不存在: {entry_path}")
    else:
        content = entry_path.read_bytes()
        sha256 = hashlib.sha256(content).hexdigest()
        expected_sha = str(frontend.get("sha256", ""))
        if expected_sha and sha256 != expected_sha:
            fail(f"前端入口 SHA256 不一致: {sha256} != {expected_sha}")
        else:
            ok(f"前端入口 {entry} SHA256 匹配")
        expected_bytes = frontend.get("bytes")
        if expected_bytes is not None and len(content) != int(expected_bytes):
            fail(f"前端入口大小不一致: {len(content)} != {expected_bytes}")
        else:
            ok(f"前端入口大小 {len(content)} B")

    backend = data.get("backend", {})
    expected_md5 = str(backend.get("jarMd5", ""))
    expected_bytes = backend.get("bytes")
    if args.jar:
        jar_path = Path(args.jar)
        if not jar_path.is_file():
            fail(f"jar 不存在: {jar_path}")
        else:
            content = jar_path.read_bytes()
            md5 = hashlib.md5(content).hexdigest()
            if md5 != expected_md5:
                fail(f"jar MD5 不一致: {md5} != {expected_md5}")
            else:
                ok(f"jar MD5 匹配 {md5}")
            if expected_bytes is not None and len(content) != int(expected_bytes):
                fail(f"jar 大小不一致: {len(content)} != {expected_bytes}")
            else:
                ok(f"jar 大小 {len(content)} B")
    else:
        print("[跳过] 未提供 --jar，未校验后端 jar")

    schema_path = Path(args.schema)
    try:
        table_count = len(
            re.findall(r"CREATE\s+TABLE", schema_path.read_text(encoding="utf-8"), re.I)
        )
    except OSError as exc:
        fail(f"读取 schema 失败: {exc}")
        table_count = -1
    expected_tables = data.get("database", {}).get("schemaTables")
    if expected_tables is None:
        fail("清单缺少 database.schemaTables")
    elif table_count == int(expected_tables):
        ok(f"schema 表数量 {table_count} 匹配")
    else:
        fail(f"schema 表数量不一致: schema={table_count} manifest={expected_tables}")

    migrations = data.get("database", {}).get("migrations", [])
    if not migrations:
        fail("清单没有列出迁移文件")
    for migration in migrations:
        path = ROOT / migration
        if path.is_file():
            ok(f"迁移文件存在 {migration}")
        else:
            fail(f"迁移文件不存在 {migration}")

    if not args.no_git:
        git_info = data.get("git", {})
        frontend_commit = str(git_info.get("frontendSourceCommit", ""))
        if frontend_commit:
            check = subprocess.run(
                ["git", "cat-file", "-e", f"{frontend_commit}^{{commit}}"],
                cwd=ROOT,
                capture_output=True,
            )
            if check.returncode != 0:
                fail(f"前端来源提交不存在: {frontend_commit}")
            else:
                diff = subprocess.run(
                    ["git", "diff", "--quiet", frontend_commit, "HEAD", "--", "frontend"],
                    cwd=ROOT,
                )
                if diff.returncode == 0:
                    ok(f"前端源码自 {frontend_commit} 未变化")
                else:
                    fail(f"前端源码在 {frontend_commit} 之后发生变化，请重新构建并更新清单")
        else:
            fail("清单缺少 git.frontendSourceCommit")

        backend_tag = str(git_info.get("backendTag", ""))
        backend_commit = str(git_info.get("backendCommit", ""))
        if backend_tag and backend_commit:
            tag_commit = subprocess.run(
                ["git", "rev-parse", f"{backend_tag}^{{commit}}"],
                cwd=ROOT,
                capture_output=True,
                text=True,
            )
            if tag_commit.returncode != 0:
                fail(f"后端标签不存在: {backend_tag}")
            elif tag_commit.stdout.strip() != backend_commit:
                fail(
                    f"后端标签提交不一致: {backend_tag}={tag_commit.stdout.strip()} "
                    f"manifest={backend_commit}"
                )
            else:
                ok(f"后端标签 {backend_tag} 指向 {backend_commit[:12]}")
        else:
            fail("清单缺少 git.backendTag/backendCommit")

    if errors:
        print(f"\n共 {len(errors)} 项失败")
        return 1
    print("\n发布清单校验全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
