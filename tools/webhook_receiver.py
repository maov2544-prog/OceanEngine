#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
webhook 接收端（本地调试用）—— 解析 POST 请求，返回 reinvestCount。

【只解析、只返回、不写任何文件】控制台打印 + HTTP 响应体，不落盘、不建目录。

启动：
    python tools/webhook_receiver.py                      # 监听 127.0.0.1:8099
    python tools/webhook_receiver.py --port 9000
    python tools/webhook_receiver.py --host 0.0.0.0 --port 9000   # 需要被外部访问时

自测（另开一个终端）：

    # ① 用本项目自带的探针（发的是真实格式的样例 payload）
    java -cp "target/classes;target/test-classes;$(cat cp.txt)" \
         com.aolandai.oceanengine.tools.WebhookProbe http://127.0.0.1:8099/

    # ② 或直接 curl
    curl -X POST http://127.0.0.1:8099/ \
         -H "Content-Type: application/json" \
         -d '{"date":"2026-09-29","orderId":"7690526648109645860","reinvestCount":5}'

Ctrl+C 退出。

依赖：只用标准库（http.server / json / argparse），无需 pip install。
"""

import argparse
import json
import sys
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# Windows 控制台默认不是 UTF-8，显式改成 UTF-8，避免中文显示成乱码。
# line_buffering=True 是关键：stdout 被重定向到文件/管道时 Python 默认用块缓冲，
# 不设这个的话「看起来什么都没打印」，要等缓冲区满才刷出来。
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace", line_buffering=True)
    except Exception:  # 极老的 Python 或已被替换的流
        pass

# 本项目 payload 的字段顺序（未列出的字段按原顺序追加在后面）
FIELD_ORDER = [
    "date", "orderId", "advertiserId", "awemeName", "awemeShowId",
    "reinvestCount", "reinvestAmount", "reason", "dryRun", "sentAt",
]

# 响应体里额外回带的字段（便于对方对账，不需要可以删）
ECHO_FIELDS = ["orderId", "date", "reason"]

LINE = "=" * 68


class Handler(BaseHTTPRequestHandler):
    server_version = "OceanEngineWebhookReceiver/1.0"
    protocol_version = "HTTP/1.1"

    # ---------- POST：正经用途 ----------

    def do_POST(self):
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            length = 0

        raw = self.rfile.read(length) if length > 0 else b""
        text = raw.decode("utf-8", errors="replace")
        stamp = datetime.now().strftime("%H:%M:%S")

        print(LINE)
        print(f"[{stamp}] POST {self.path}   来自 {self.client_address[0]}   {length} 字节")

        # ---- 解析 ----
        if not text.strip():
            self._fail(400, "请求体为空", text)
            return
        try:
            payload = json.loads(text)
        except json.JSONDecodeError as exc:
            self._fail(400, f"不是合法 JSON：{exc}", text)
            return
        if not isinstance(payload, dict):
            self._fail(400, f"期望 JSON 对象，实际是 {type(payload).__name__}", text)
            return

        # ---- 打印：按固定字段顺序，未知字段追加在后 ----
        ordered = [k for k in FIELD_ORDER if k in payload]
        ordered += [k for k in payload if k not in FIELD_ORDER]
        for key in ordered:
            mark = "   <<<<" if key == "reinvestCount" else ""
            print(f"    {key:<16} = {payload[key]!r}{mark}")

        # ---- 取 reinvestCount ----
        count = payload.get("reinvestCount")
        print("-" * 68)
        if count is None:
            print("    ==> reinvestCount 字段不存在（或为 null）")
        else:
            print(f"    ==> reinvestCount = {count}")

        # ---- 响应 ----
        body = {"ok": True, "reinvestCount": count}
        for key in ECHO_FIELDS:
            if key in payload:
                body[key] = payload[key]
        self._respond(200, body)

    # ---------- GET：确认服务活着 ----------

    def do_GET(self):
        self._respond(200, {
            "ok": True,
            "hint": "请用 POST 发 JSON 过来。本服务只解析并返回 reinvestCount，不写任何文件。",
        })

    # ---------- 其它方法 ----------

    def do_PUT(self):
        self._respond(405, {"ok": False, "error": "只接受 POST"})

    def do_DELETE(self):
        self._respond(405, {"ok": False, "error": "只接受 POST"})

    # ---------- 工具 ----------

    def _fail(self, status, message, raw_text=""):
        print(f"    !! {message}")
        if raw_text:
            print(f"    原始内容: {raw_text[:500]}")
        self._respond(status, {"ok": False, "error": message, "reinvestCount": None})

    def _respond(self, status, body):
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        try:
            self.wfile.write(data)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            # 对方提前挂了电话，不影响本次处理结果
            self.close_connection = True

    def handle_one_request(self):
        """
        吞掉「客户端提前断开连接」的噪音。

        HTTP/1.1 是 keep-alive 的：响应发完后服务端还会读下一个请求，
        而 JDK HttpClient / curl 这类客户端常直接关连接 → 抛 ConnectionResetError，
        被 socketserver 打成一大段堆栈。请求本身其实已经成功处理完了，所以这里静默。
        """
        try:
            super().handle_one_request()
        except (ConnectionResetError, BrokenPipeError, ConnectionAbortedError):
            self.close_connection = True

    def log_message(self, fmt, *args):
        """关掉 http.server 默认的每请求一行日志，避免和上面的输出重复。"""
        return


def main():
    parser = argparse.ArgumentParser(
        description="webhook 接收端：解析 POST 请求，返回 reinvestCount（不写任何文件）")
    parser.add_argument("--host", default="127.0.0.1",
                        help="监听地址，默认 127.0.0.1（只本机可访问）；"
                             "要让外部访问传 0.0.0.0")
    parser.add_argument("--port", type=int, default=8099,
                        help="监听端口，默认 8099")
    args = parser.parse_args()

    try:
        server = ThreadingHTTPServer((args.host, args.port), Handler)
    except OSError as exc:
        print(f"启动失败：{exc}")
        print(f"→ 端口 {args.port} 可能已被占用。换一个：--port 9000")
        sys.exit(1)

    print(LINE)
    print(f"webhook 接收端已启动：http://{args.host}:{args.port}/")
    print("只解析并返回 reinvestCount，不写任何文件。Ctrl+C 退出。")
    print(LINE)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n已停止。")
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
