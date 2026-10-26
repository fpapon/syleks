#!/usr/bin/env python3
"""SEC-10 packaged PTY regression; synthetic commands, no provider/server required.
Run after mvn verify with Java 25 on PATH or JAVA_HOME set.
"""
import fcntl
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
import time

repo = pathlib.Path(__file__).resolve().parent.parent
java = str(pathlib.Path(os.environ['JAVA_HOME']) / 'bin/java') if 'JAVA_HOME' in os.environ else shutil.which('java')
jar = repo / 'target/syleks-0.1.0-SNAPSHOT.jar'
with tempfile.TemporaryDirectory(prefix='syleks-sec10-') as directory:
    root = pathlib.Path(directory)
    home, project = root / 'home', root / 'project'
    (home / '.config/syleks').mkdir(parents=True)
    project.mkdir()
    (home / '.config/syleks/agent-config.json').write_text(json.dumps({
        'model': 'ollama/qwen3:8b', 'provider': {'ollama': {'options': {'baseURL': 'http://127.0.0.1:1'}}}}))
    env = {k: v for k, v in os.environ.items() if not k.startswith(('SYLEKS_', 'OLLAMA_', 'AGENT_', 'XDG_'))}
    env.update(HOME=str(home), SYLEKS_DATA_DIR=str(root / 'data'), TERM='xterm-256color')
    base = [java, '--enable-native-access=ALL-UNNAMED', '-Duser.home=' + str(home)]
    created = subprocess.run(base + ['-cp', str(repo / 'target/test-classes') + os.pathsep + str(jar),
        'fr.openobject.ai.syleks.session.SessionProcess', str(root), 'create'],
        cwd=project, env=env, capture_output=True, text=True, timeout=20, check=True)
    session = created.stdout.strip()
    with sqlite3.connect(root / 'data/syleks.db') as db:
        initial_messages = db.execute('SELECT count(*) FROM messages WHERE session_id=?', (session,)).fetchone()[0]
    master, slave = pty.openpty()
    fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack('HHHH', 36, 120, 0, 0))
    process = subprocess.Popen(base + ['-jar', str(jar), '--session', session], cwd=project, env=env,
        stdin=slave, stdout=slave, stderr=slave, start_new_session=True)
    os.close(slave)
    captured = bytearray()
    checks = []

    def drain(seconds=.45):
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

    def title(expected):
        deadline = time.monotonic() + 8
        while True:
            with sqlite3.connect(root / 'data/syleks.db') as db:
                actual = db.execute('SELECT title FROM sessions WHERE id=?', (session,)).fetchone()[0]
            if actual == expected:
                drain(.5)  # Let the terminal consume the session worker completion.
                return
            assert time.monotonic() < deadline, (actual, expected)
            drain(.2)

    def rename(value, private=False):
        send((' ' if private else '') + '/rename ' + value + '\r')
        title(value)

    def resize(rows, columns):
        fcntl.ioctl(master, termios.TIOCSWINSZ, struct.pack('HHHH', rows, columns, 0, 0))
        os.kill(process.pid, signal.SIGWINCH)
        drain()

    try:
        drain(2)
        rename('recalled-🧪')
        rename('excluded', private=True)
        send('\x1b[A\r')
        title('recalled-🧪')
        checks.append('leading-space command excluded; ordinary Unicode command recalled')
        send('/history clear\r')
        rename('cleared', private=True)
        send('\x1b[A\r')
        title('cleared')
        checks.append('clear does not reinsert itself')
        send('/history off\r')
        rename('disabled-secret')
        rename('disabled-baseline', private=True)
        send('\x1b[A\r')
        title('disabled-baseline')
        preference = home / '.config/syleks/config.json'
        assert json.loads(preference.read_text())['input_history'] is False
        send(' /restart\r')
        drain(1.5)
        send('\x1b[A\r')
        title('disabled-baseline')
        checks.append('disabled collection persists across fresh runtime restart')
        send('/history on\r')
        rename('enabled')
        rename('baseline', private=True)
        send('\x1b[A\r')
        title('enabled')
        checks.append('reenabling collects only new entries')
        send('\x18')  # Ctrl+X clears in the composer.
        rename('after-shortcut', private=True)
        send(' /restart\r')
        drain(1.5)
        send('\x1b[A\r')
        title('after-shortcut')
        checks.append('clear then restart cannot resurrect recall')
        send('\x10')  # Help owns arrows and paging.
        send('\x1b[F')
        send('\x1b[5~')
        send('\x1b[6~')
        send('\x1b[H')
        resize(14, 40)
        resize(36, 120)
        send('\r')
        send('\x1b[5~')
        send('\x1b[6~')
        send('\x07')  # Configuration modal owns typed text.
        send('\t\t\t')  # API-key field.
        before_secret = len(captured)
        send('synthetic-dialog-secret')
        assert b'synthetic-dialog-secret' not in captured[before_secret:], 'Modal key was not masked'
        assert '•'.encode() in captured[before_secret:], 'Secret field did not render masking bullets'
        send('\x1b')
        send('\x1b[A\r')
        title('after-shortcut')
        checks.append('masked modal key excluded from recall; help paging and resize')
        send('/quit\r')
        process.wait(timeout=10)
        assert process.returncode == 0, process.returncode
        assert b'Exception' not in captured
        with sqlite3.connect(root / 'data/syleks.db') as db:
            assert db.execute('SELECT count(*) FROM messages WHERE session_id=?', (session,)).fetchone()[0] == initial_messages, 'Empty recall or modal input created a provider prompt'
        checks.append('no unintended submitted messages; clean exit without terminal exceptions')
        print(json.dumps({'status': 'PASS', 'checks': checks}, indent=2))
    finally:
        output = repo / 'target/sec10'
        output.mkdir(parents=True, exist_ok=True)
        (output / 'terminal-output.bin').write_bytes(captured)
        if process.poll() is None:
            process.kill()
            process.wait()
        os.close(master)
