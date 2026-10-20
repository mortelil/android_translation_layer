#!/usr/bin/env python3
"""Loopback fixture server: requires a header and supports byte-range seeking."""
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
import sys
payload = Path(sys.argv[1]).read_bytes()
class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.headers.get('X-ATL-Probe') != 'required':
            self.send_error(403)
            return
        start, end = 0, len(payload) - 1
        interval = self.headers.get('Range')
        if interval:
            lo, hi = interval.removeprefix('bytes=').split('-', 1)
            start = int(lo)
            if hi: end = min(int(hi), end)
        self.send_response(206 if interval else 200)
        self.send_header('Content-Type', 'video/mp4')
        self.send_header('Accept-Ranges', 'bytes')
        self.send_header('Content-Length', str(end - start + 1))
        if interval: self.send_header('Content-Range', f'bytes {start}-{end}/{len(payload)}')
        self.end_headers()
        self.wfile.write(payload[start:end + 1])
HTTPServer(('127.0.0.1', 18963), Handler).serve_forever()
