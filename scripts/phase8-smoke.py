#!/usr/bin/env python3
"""Packaged TUI smoke in a real PTY, against a local fixture; no external model.
Run after mvn verify, with Java 25 on PATH or JAVA_HOME set (Linux/macOS).
"""
import fcntl
import http.server
import json
import os
import pathlib
import pty
import select
import shutil
import signal
import sqlite3
import struct
import subprocess
import tempfile
import termios
import threading
import time
import uuid

repo = pathlib.Path(__file__).resolve().parent.parent
java = str(pathlib.Path(os.environ['JAVA_HOME']) / 'bin/java') if 'JAVA_HOME' in os.environ else shutil.which('java')
jar = repo / 'target/syleks-0.1.0-SNAPSHOT.jar'
started, disconnected = threading.Event(), threading.Event()


class Provider(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_POST(self):
        self.rfile.read(int(self.headers['Content-Length']))
        self.send_response(200)
        self.send_header('Content-Type', 'application/x-ndjson')
        self.end_headers()
        started.set()
        try:
            for i in range(200):
                self.wfile.write((json.dumps({'message': {'role': 'assistant', 'content': f'fixture-stream-{i} '}, 'done': False}) + '\n').encode())
                self.wfile.flush()
                time.sleep(.05)
        except (BrokenPipeError, ConnectionResetError):
            disconnected.set()


server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Provider)
threading.Thread(target=server.serve_forever, daemon=True).start()
with tempfile.TemporaryDirectory(prefix='syleks-phase8-') as directory:
    root = pathlib.Path(directory)
    home, project = root / 'home', root / 'project'
    (home / '.config/syleks').mkdir(parents=True)
    project.mkdir()
    (home / '.config/syleks/agent-config.json').write_text(json.dumps({'permission': 'allow', 'model': 'ollama/qwen3:8b', 'provider': {'ollama': {'options': {'baseURL': f'http://127.0.0.1:{server.server_port}'}}}}))
    env = {k: v for k, v in os.environ.items() if not k.startswith(('SYLEKS_', 'OLLAMA_', 'AGENT_', 'XDG_'))}
    env.update(HOME=str(home), SYLEKS_DATA_DIR=str(root / 'data'), TERM='xterm-256color',
               SYLEKS_CONTEXT_WINDOW='65536', SYLEKS_RESPONSE_TOKENS='2048')
    base = [java, '--enable-native-access=ALL-UNNAMED', '-Duser.home=' + str(home)]
    create = subprocess.run(base + ['-cp', str(repo / 'target/test-classes') + os.pathsep + str(jar),
                            'fr.openobject.ai.syleks.session.SessionProcess', str(root), 'create'],
                            cwd=project, env=env, capture_output=True, text=True, timeout=20, check=True)
    session = str(uuid.UUID(create.stdout.strip()))
    # Large saved transcript, independent of fixture helper implementation details.
    with sqlite3.connect(root / 'data/syleks.db') as db:
        position = db.execute('SELECT max(position) FROM messages WHERE session_id=?', (session,)).fetchone()[0] + 1
        stamp = '2026-10-19T10:00:00Z'
        for i in range(400):
            mid = str(uuid.uuid4())
            db.execute('INSERT INTO messages VALUES(?,?,?,?,?,?,?)',
                       (mid, session, 'user' if i % 2 == 0 else 'assistant', 'COMPLETE', stamp, stamp, position + i))
            db.execute('INSERT INTO message_parts VALUES(?,?,?,?,?,?)',
                       (str(uuid.uuid4()), mid, 0, 'TEXT', json.dumps({'text': f'Fixture history {i} 界 🧪\n' * 8}), 1))
    master, slave = pty.openpty()
    fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack('HHHH', 36, 120, 0, 0))
    process = subprocess.Popen(base + ['-jar', str(jar), '--session', session], cwd=project, env=env,
                               stdin=slave, stdout=slave, stderr=slave, start_new_session=True)
    os.close(slave)
    captured = bytearray()

    def drain(seconds=.25):
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            ready, _, _ = select.select([master], [], [], min(.05, max(0, end - time.monotonic())))
            if ready:
                try:
                    data = os.read(master, 65536)
                except OSError:
                    return
                if not data:
                    return
                captured.extend(data)

    def send(value):
        os.write(master, value.encode())
        drain()

    def resize(rows, columns):
        fcntl.ioctl(master, termios.TIOCSWINSZ, struct.pack('HHHH', rows, columns, 0, 0))
        os.kill(process.pid, signal.SIGWINCH)
        drain()

    try:
        drain(1.5)
        send('/help\r')
        send('\x1b[F')
        send('\x1b[H')
        send('\r')
        send('/sessions\r')
        send('\x1b[F')
        send('\x1b[5~')
        send('\x1b[H')
        send('\r')
        send('/usage\r')
        for _ in range(3):
            send('\x1b[6~')
        send('\x1b[F')
        send('\x1b[H')
        resize(14, 40)
        resize(36, 120)
        send('\x1b')
        send('fixture request\r')
        deadline = time.monotonic() + 10
        while not started.is_set() and time.monotonic() < deadline:
            drain()
        assert started.is_set(), 'No request reached the local fixture'
        drain(.4)
        resize(24, 80)
        send('\x1b[5~')
        resize(36, 120)
        cancel = time.monotonic()
        send('\x1b')
        while not disconnected.is_set() and time.monotonic() - cancel < 5:
            drain()
        assert disconnected.is_set(), 'Cancellation did not close the streaming response'
        cancellation_ms = round((time.monotonic() - cancel) * 1000)
        send('/usage\r')
        send('\x1b[F')
        send('\x1b[H')
        send('\x1b')
        send('/quit\r')
        process.wait(timeout=8)
        assert process.returncode == 0, process.returncode
        assert b'Exception' not in captured, 'Unexpected terminal exception'
        with sqlite3.connect(root / 'data/syleks.db') as db:
            assert db.execute("SELECT count(*) FROM llm_usage WHERE session_id=? AND status='INTERRUPTED'", (session,)).fetchone()[0] == 1
        report = repo / 'target/performance'
        report.mkdir(parents=True, exist_ok=True)
        (report / 'terminal.json').write_text(json.dumps({'status': 'PASS', 'historyMessages': 403,
            'cancelObservedMs': cancellation_ms, 'sizes': ['120x36', '40x14', '80x24'],
            'checks': ['help', 'session navigation', 'usage navigation', 'resize', 'stream cancellation', 'persisted interrupted usage', 'clean exit']}, indent=2) + '\n')
        (report / 'terminal-output.bin').write_bytes(captured)
        print(f'PTY smoke PASS; cancellation observed in {cancellation_ms} ms; reports: {report}')
    finally:
        report = repo / 'target/performance'
        report.mkdir(parents=True, exist_ok=True)
        (report / 'terminal-output.bin').write_bytes(captured)
        if process.poll() is None:
            process.kill()
            process.wait()
        os.close(master)
        server.shutdown()
        server.server_close()
