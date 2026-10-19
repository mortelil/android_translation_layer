#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
from http.server import BaseHTTPRequestHandler, HTTPServer
class Fixture(BaseHTTPRequestHandler):
    def log_message(self, *args): pass
    def do_GET(self):
        self.send_response(200)
        self.send_header('Content-Type', 'text/html; charset=utf-8')
        self.send_header('Set-Cookie', 'server=private; HttpOnly; Path=/')
        self.end_headers()
        seen = 'java=before' in self.headers.get('Cookie', '')
        self.wfile.write(("<title>Cookie title 🎵</title><script>document.cookie='script=after; Path=/';window.sent=" +
                         str(seen).lower() + ";</script><p>Local cookie fixture</p>").encode())
HTTPServer(('127.0.0.1', 18761), Fixture).serve_forever()
