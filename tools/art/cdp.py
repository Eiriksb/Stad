"""A tiny Chrome DevTools Protocol client (stdlib only), for driving Blockbench (an Electron app) from scripts.

Start Blockbench with --remote-debugging-port=<port>, then Cdp(port).eval("Blockbench.version").
"""
import base64
import json
import os
import socket
import struct
import urllib.request


class Cdp:
    def __init__(self, port=9333, host="127.0.0.1"):
        pages = json.load(urllib.request.urlopen(f"http://{host}:{port}/json"))
        page = next(p for p in pages if p.get("type") == "page")
        url = page["webSocketDebuggerUrl"]
        path = url.split(f"{host}:{port}", 1)[1]
        self.sock = socket.create_connection((host, port))
        key = base64.b64encode(os.urandom(16)).decode()
        self.sock.sendall((f"GET {path} HTTP/1.1\r\nHost: {host}:{port}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                           f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n").encode())
        head = b""
        while b"\r\n\r\n" not in head:
            head += self.sock.recv(1)
        if b" 101 " not in head.split(b"\r\n")[0]:
            raise RuntimeError(head.decode(errors="replace"))
        self.next_id = 0

    def _send(self, text):
        data = text.encode()
        mask = os.urandom(4)
        n = len(data)
        header = bytes([0x81])
        if n < 126:
            header += bytes([0x80 | n])
        elif n < 65536:
            header += bytes([0x80 | 126]) + struct.pack(">H", n)
        else:
            header += bytes([0x80 | 127]) + struct.pack(">Q", n)
        self.sock.sendall(header + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(data)))

    def _exact(self, n):
        buf = b""
        while len(buf) < n:
            chunk = self.sock.recv(n - len(buf))
            if not chunk:
                raise EOFError
            buf += chunk
        return buf

    def _recv(self):
        message = b""
        while True:
            b0, b1 = self._exact(2)
            n = b1 & 0x7F
            if n == 126:
                n = struct.unpack(">H", self._exact(2))[0]
            elif n == 127:
                n = struct.unpack(">Q", self._exact(8))[0]
            payload = self._exact(n)
            if b0 & 0x0F == 9:  # ping
                continue
            message += payload
            if b0 & 0x80:
                return message.decode()

    def call(self, method, **params):
        self.next_id += 1
        mid = self.next_id
        self._send(json.dumps({"id": mid, "method": method, "params": params}))
        while True:
            msg = json.loads(self._recv())
            if msg.get("id") == mid:
                if "error" in msg:
                    raise RuntimeError(msg["error"])
                return msg["result"]

    def eval(self, js):
        """Evaluates js (an expression, or an async function body via (async () => {...})()) and returns its value."""
        r = self.call("Runtime.evaluate", expression=js, awaitPromise=True, returnByValue=True)
        if "exceptionDetails" in r:
            raise RuntimeError(r["exceptionDetails"].get("exception", {}).get("description") or r["exceptionDetails"])
        return r["result"].get("value")
