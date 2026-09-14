#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从 CHANGELOG.md 生成 GitHub Release 说明页（含更新日志）。

规则：
  - 版本段落取自 CHANGELOG.md 的 `## <version>` 标题及其正文。
  - 正式版 tag 形如 `<versionCode>-<versionName>`（如 284-alpha2.41.10）。
  - 自动回溯到「上一个正式版 tag」，把两次发布之间的全部段落一并纳入，
    避免中间未单独发过正式版的 alpha 修复被用户漏看。

用法：
  python3 tools/release_notes.py --tag 284-alpha2.41.10 --sha <sha>
  python3 tools/release_notes.py --tag 284-alpha2.41.10 --prev 280-alpha2.41.6 --output body.md
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
CHANGELOG = REPO_ROOT / "CHANGELOG.md"

TAG_RE = re.compile(r"^(\d+)-(.+)$")
HEAD_RE = re.compile(r"^##\s+((?:alpha|beta|v)?\d[\w.]*)")  # 前缀可选：兼容 alpha2.41.10 与 2.50
FENCE_RE = re.compile(r"^-{3,}$")


def git_tags():
    """读取本地 tag 列表；失败时返回空列表（脚本退化为单版本输出）。"""
    try:
        out = subprocess.run(
            ["git", "tag"], cwd=REPO_ROOT,
            capture_output=True, text=True, check=True,
        )
    except Exception:
        return []
    return [t.strip() for t in out.stdout.splitlines() if t.strip()]


def git_repo_slug():
    """从 origin 解析 owner/repo。"""
    try:
        out = subprocess.run(
            ["git", "remote", "get-url", "origin"], cwd=REPO_ROOT,
            capture_output=True, text=True, check=True,
        ).stdout.strip()
    except Exception:
        return None
    m = re.search(r"github\.com[:/](.+?)(?:\.git)?$", out)
    return m.group(1) if m else None


def git_head_sha():
    try:
        return subprocess.run(
            ["git", "rev-parse", "HEAD"], cwd=REPO_ROOT,
            capture_output=True, text=True, check=True,
        ).stdout.strip()
    except Exception:
        return None


def parse_tag(tag):
    """`284-alpha2.41.10` -> (284, 'alpha2.41.10')；非版本号 tag 返回 None。"""
    m = TAG_RE.match(tag or "")
    return (int(m.group(1)), m.group(2)) if m else None


def prev_official_tag(tag, tags):
    """按 versionCode 找小于当前版本的最大官方 tag（跳过 ci-N 与无编号 tag）。"""
    cur = parse_tag(tag)
    if not cur:
        return None
    best = None
    for t in tags:
        if t.startswith("ci-"):
            continue
        p = parse_tag(t)
        if not p or p[0] >= cur[0]:
            continue
        if best is None or p[0] > best[0]:
            best = (p[0], t)
    return best[1] if best else None


def split_sections(text):
    """切成 [(version, block)]，block 含标题行，保持文件顺序（新 -> 旧）。"""
    starts = [m.start() for m in re.finditer(r"^##\s", text, flags=re.M)]
    out = []
    for i, s in enumerate(starts):
        e = starts[i + 1] if i + 1 < len(starts) else len(text)
        block = text[s:e]
        m = HEAD_RE.match(block)
        out.append((m.group(1) if m else None, block.rstrip()))
    return out


def clean(block):
    """去掉段落末尾残留的 `---` 分隔线与空白。"""
    lines = block.rstrip().splitlines()
    while lines and FENCE_RE.match(lines[-1].strip()):
        lines.pop()
    return "\n".join(lines).rstrip()


def demote(block):
    """标题整体降一级，避免与发布页大标题抢层级。"""
    return re.sub(r"^(#+)", lambda m: m.group(1) + "#", block, flags=re.M)


def build_body(tag, sha, repo, changelog_text, tags, prev_override):
    """返回 (body, 说明信息)。"""
    cur = parse_tag(tag)
    cur_ver = cur[1] if cur else tag

    prev_tag = prev_override or prev_official_tag(tag, tags)
    prev_parsed = parse_tag(prev_tag) if prev_tag else None
    prev_ver = prev_parsed[1] if prev_parsed else None

    sections = split_sections(changelog_text)
    cur_i = next((i for i, (v, _) in enumerate(sections) if v == cur_ver), None)
    prev_i = next((i for i, (v, _) in enumerate(sections) if prev_ver and v == prev_ver), None)

    note = ""
    picked = []
    if cur_i is None:
        note = "未在 CHANGELOG.md 中找到 %s，回退为提交链接。" % cur_ver
    else:
        if prev_ver and prev_i is None:
            note = ("未找到上一正式版 %s 的段落，仅输出当前版本，"
                    "同为 %s 的中间版本可能被遗漏。" % (prev_ver, cur_ver))
        end = prev_i if (prev_i is not None and prev_i > cur_i) else cur_i + 1
        picked = [clean(b) for _, b in sections[cur_i:end] if b.strip()]

    lines = ["## 正式发布 `%s`" % tag, ""]
    if sha:
        lines.append("- 提交：`%s`" % sha)
    lines.append("- 触发：tag `%s`" % tag)
    lines.append("- 构建方式：Release（作用域文件 `META-INF/xposed/*` 由 AGP 打包时合并）")
    lines.append("- 签名：CN=FxxkMoondrop（APK Signature Scheme v2）")
    lines += ["", "## 更新日志", ""]

    if prev_tag and prev_ver:
        lines.append("> 自上个正式版 `%s` 以来的全部变更。" % prev_tag)
    else:
        lines.append("> 本版本更新内容。")
    lines.append("")

    if picked:
        lines.append("\n\n".join(demote(b) for b in picked))
    else:
        link = "https://github.com/%s/commits/main" % repo if repo else "仓库 commits"
        lines.append("详见 [Commits](%s)。" % link)
    lines.append("")

    return "\n".join(lines), note


def main():
    ap = argparse.ArgumentParser(description="生成 Release 说明页（含 CHANGELOG 更新日志）")
    ap.add_argument("--tag", required=True, help="形如 284-alpha2.41.10")
    ap.add_argument("--sha", default=None, help="提交 SHA，默认取 HEAD")
    ap.add_argument("--repo", default=None, help="owner/repo，默认从 origin 解析")
    ap.add_argument("--prev", default=None, help="手动指定上一正式版 tag")
    ap.add_argument("--changelog", default=str(CHANGELOG), help="CHANGELOG 路径")
    ap.add_argument("--output", default=None, help="输出文件，默认 stdout")
    ap.add_argument("--meta-json", default=None, help="额外输出 JSON（供 curl -d @ 使用）")
    ap.add_argument("--payload-json", default=None,
                    help="输出完整 release payload JSON（tag_name/name/prerelease/body）")
    ap.add_argument("--payload-name", default=None, help="release 显示名，默认同 tag")
    ap.add_argument("--prerelease", default="false", choices=["true", "false"],
                    help="是否为预发行版")
    args = ap.parse_args()

    path = Path(args.changelog)
    if not path.is_file():
        print("错误：找不到 %s" % path, file=sys.stderr)
        return 1

    body, note = build_body(
        tag=args.tag,
        sha=args.sha or git_head_sha(),
        repo=args.repo or git_repo_slug(),
        changelog_text=path.read_text(encoding="utf-8"),
        tags=git_tags(),
        prev_override=args.prev,
    )

    if args.output:
        Path(args.output).write_text(body, encoding="utf-8")
        print("已写入 %s（%d 字符）" % (args.output, len(body)), file=sys.stderr)
    else:
        print(body)

    if args.meta_json:
        import json
        Path(args.meta_json).write_text(
            json.dumps({"body": body}, ensure_ascii=False), encoding="utf-8")
        print("已写入 %s" % args.meta_json, file=sys.stderr)

    if args.payload_json:
        import json
        payload = {
            "tag_name": args.tag,
            "name": args.payload_name or args.tag,
            "prerelease": args.prerelease == "true",
            "body": body,
        }
        Path(args.payload_json).write_text(
            json.dumps(payload, ensure_ascii=False), encoding="utf-8")
        print("已写入 %s（prerelease=%s）" % (args.payload_json, args.prerelease),
              file=sys.stderr)

    if note:
        print(note, file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
