#!/usr/bin/env python3
"""Gitee Webhook 自动分类打标签（Issue + PR）。

Gitee Go 没有 issue 触发器，也没有内置打标签插件，因此用一个极简 HTTP 服务接收
Gitee Webhook（hook_name: issue_hooks / merge_request_hooks），按标题/正文关键词
与 Conventional Commit 类型调用 Gitee Open API 给 Issue / PR 打标签。

仅使用 Python 标准库，无第三方依赖。

环境变量：
  GITEE_TOKEN          必填，具备仓库写权限的个人访问令牌
  GITEE_OWNER          必填，仓库拥有者
  GITEE_REPO           必填，仓库名
  GITEE_WEBHOOK_SECRET 可选，Webhook 中配置的密码，用于校验 X-Gitee-Token
  LABEL_HOST / LABEL_PORT  监听地址，默认 0.0.0.0 / 18081

运行：
  python3 scripts/gitee-webhook-labeler.py
然后在 Gitee 仓库「WebHooks」添加 URL，勾选 Issue 与 Pull Request 事件。
"""

import json
import os
import re
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TOKEN = os.environ.get("GITEE_TOKEN", "")
OWNER = os.environ.get("GITEE_OWNER", "")
REPO = os.environ.get("GITEE_REPO", "")
SECRET = os.environ.get("GITEE_WEBHOOK_SECRET", "")

# 标题/正文关键词 -> label
ISSUE_RULES = [
    (re.compile(r"内存|memory|oom|heap|占用", re.I), "kind/memory"),
    (re.compile(r"性能|慢|卡顿|performance|slow", re.I), "kind/performance"),
    (re.compile(r"bug|错误|异常|失败|error|exception|crash", re.I), "kind/bug"),
    (re.compile(r"文档|README|docs|说明", re.I), "kind/documentation"),
    (re.compile(r"rag|检索|切分|向量|qdrant|sqlite|embedding", re.I), "kind/rag"),
    (re.compile(r"mcp|工具|tool", re.I), "kind/mcp"),
    (re.compile(r"cicd|ci|cd|流水线|pipeline|gitee|action", re.I), "kind/ci"),
    (re.compile(r"需求|feature|功能|希望|建议", re.I), "kind/enhancement"),
]

COMMIT_RULES = [
    (re.compile(r"^feat(\(|:\s)", re.I), "type/feature"),
    (re.compile(r"^fix(\(|:\s)", re.I), "type/bug"),
    (re.compile(r"^docs(\(|:\s)", re.I), "type/documentation"),
    (re.compile(r"^chore(\(|:\s)", re.I), "type/chore"),
    (re.compile(r"^refactor(\(|:\s)", re.I), "type/refactor"),
    (re.compile(r"^perf(\(|:\s)", re.I), "type/performance"),
    (re.compile(r"^test(\(|:\s)", re.I), "type/test"),
    (re.compile(r"^build(\(|:\s)", re.I), "type/build"),
    (re.compile(r"^ci(\(|:\s)", re.I), "type/ci"),
    (re.compile(r"^hotfix(\(|:\s)", re.I), "type/hotfix"),
]


def labels_for(text, prefix):
    return [label for pattern, label in prefix if pattern.search(text)]


def add_labels(number, kind, labels):
    if not labels:
        return
    endpoint = (
        f"https://gitee.com/api/v5/repos/{OWNER}/{REPO}"
        f"/{'issues' if kind == 'issue' else 'pulls'}/{number}/labels"
    )
    body = json.dumps({"access_token": TOKEN, "labels": labels}).encode("utf-8")
    request = urllib.request.Request(
        endpoint,
        data=body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=15) as response:
        print(f"{kind} #{number} -> {labels} (HTTP {response.status})")


def handle(payload):
    hook = payload.get("hook_name", "")
    if hook == "issue_hooks":
        issue = payload.get("issue", {})
        number = issue.get("number")
        text = f"{issue.get('title', '')}\n{issue.get('body', '')}"
        labels = labels_for(text, ISSUE_RULES)
        add_labels(number, "issue", labels)
    elif hook in ("merge_request_hooks", "pull_request_hooks"):
        pr = payload.get("pull_request", payload.get("merge_request", {}))
        number = pr.get("number")
        title = pr.get("title", "")
        labels = labels_for(title, COMMIT_RULES)
        # 目标分支 -> 环境标签
        target = (pr.get("base") or {}).get("ref", "")
        if target == "develop":
            labels.append("env/dev")
        elif target.startswith("release/"):
            labels.append("env/staging")
        elif target == "main":
            labels.append("env/production")
        add_labels(number, "pr", labels)


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        if SECRET and self.headers.get("X-Gitee-Token") != SECRET:
            self.send_response(403)
            self.end_headers()
            return
        length = int(self.headers.get("Content-Length", 0))
        try:
            payload = json.loads(self.rfile.read(length))
            handle(payload)
            self.send_response(200)
        except Exception as exc:  # noqa: BLE001 - webhook 不应因异常中断
            print(f"处理失败: {exc}")
            self.send_response(500)
        self.end_headers()

    def log_message(self, fmt, *args):
        print(f"{self.address_string()} - {fmt % args}")


if __name__ == "__main__":
    if not all((TOKEN, OWNER, REPO)):
        raise SystemExit("请设置 GITEE_TOKEN / GITEE_OWNER / GITEE_REPO")
    host = os.environ.get("LABEL_HOST", "0.0.0.0")
    port = int(os.environ.get("LABEL_PORT", "18081"))
    print(f"Gitee labeler listening on {host}:{port}")
    ThreadingHTTPServer((host, port), Handler).serve_forever()
