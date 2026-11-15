#!/usr/bin/env python3
"""Disposable outage proxy for synthetic native acceptance only.

Requires the synthetic API on loopback18751 with the public dummy owner key
"a" *64. Listens on loopback18754; never point the upstream at a real journal.
The test-only control route changes failures/counters in memory. No content logs.
Stop the process and remove any temporary TLS mapping after acceptance.
"""
import http.server, json, urllib.request, urllib.error, socket
mode = 'up'
reads = 0
uploads = 0

class Handler(http.server.BaseHTTPRequestHandler):

    def log_message(self, *args):
        pass

    def answer(self, status, data):
        raw = json.dumps(data).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Cache-Control', 'no-store')
        self.send_header('Content-Length', str(len(raw)))
        self.send_header('Retry-After', '120')
        self.end_headers()
        self.wfile.write(raw)

    def run_request(self):
        global mode, reads, uploads
        if self.headers.get('Authorization') != 'Bearer ' + 'a' * 64:
            return self.answer(401, {'error': 'synthetic key required'})
        size = int(self.headers.get('Content-Length', '0'))
        if size > 65536:
            return self.answer(413, {'error': 'bound'})
        data = self.rfile.read(size) if size else None
        if self.path == '/v1/__synthetic_control':
            if self.command == 'POST':
                proposed = json.loads(data)['mode']
                if proposed not in ['up', 'busy', 'transport', 'unauthorized', 'forbidden']:
                    return self.answer(400, {'error': 'mode'})
                mode = proposed
                reads = 0
                uploads = 0
            return self.answer(200, {'mode': mode, 'control_reads': reads, 'uploads': uploads})
        if self.path == '/v1/collection' and self.command == 'GET':
            reads += 1
            if mode == 'transport':
                self.close_connection = True
                self.connection.shutdown(socket.SHUT_RDWR)
                self.connection.close()
                return
            if mode != 'up':
                return self.answer({'busy': 503, 'unauthorized': 401, 'forbidden': 403}[mode], {'error': 'synthetic control failure'})
        if self.command == 'POST' and self.path in ['/v1/activities', '/v1/observations', '/v1/reflections', '/v1/cards']:
            uploads += 1
        request = urllib.request.Request('http://127.0.0.1:18751' + self.path, data=data, headers={'Authorization': self.headers['Authorization'], 'Content-Type': 'application/json'}, method=self.command)
        try:
            response = urllib.request.urlopen(request, timeout=12)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw = response.read(524289)
            self.send_response(response.status)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(raw)))
            self.send_header('Cache-Control', 'no-store')
            self.end_headers()
            self.wfile.write(raw)
    do_GET = run_request
    do_POST = run_request
    do_PUT = run_request
    do_DELETE = run_request
http.server.HTTPServer(('127.0.0.1', 18754), Handler).serve_forever()
