"""Manage a project-private MySQL instance and a loopback-only Django server."""
import argparse
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import sys
import time
from urllib.request import urlopen

import MySQLdb
import psutil

ROOT = Path(__file__).resolve().parent.parent
STATE = ROOT / '.local'
CONFIG_PATH = STATE / 'runtime.json'
MANAGE = ROOT / 'local-dev/backend/manage.py'
DB_PORT = 3307
API_PORT = 8001
FLAGS = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0


def load_config():
    if not CONFIG_PATH.exists():
        raise RuntimeError('Run setup first.')
    return json.loads(CONFIG_PATH.read_text(encoding='utf-8'))


def port_open(port):
    try:
        with socket.create_connection(('127.0.0.1', port), timeout=0.5):
            return True
    except OSError:
        return False


def root_connection(config):
    conn = MySQLdb.connect(host='127.0.0.1', port=config['db_port'], user='root',
                           passwd=config['root_password'], connect_timeout=2)
    with conn.cursor() as cursor:
        cursor.execute('SELECT @@datadir')
        actual = Path(cursor.fetchone()[0]).resolve()
    if actual != (STATE / 'mysql').resolve():
        conn.close()
        raise RuntimeError('Refusing to manage a MySQL instance outside this project.')
    return conn


def setup(mysql_bin):
    STATE.mkdir(exist_ok=True)
    if CONFIG_PATH.exists():
        print('Existing local configuration retained.')
        return
    binary = Path(mysql_bin or os.environ.get('STYLEMATE_MYSQL_BIN',
        'C:/Program Files/MySQL/MySQL Server 8.0/bin')) / 'mysqld.exe'
    if not binary.is_file():
        raise RuntimeError('MySQL binary not found; pass --mysql-bin / -MySqlBin.')
    config = {'mysql_binary': str(binary), 'db_port': DB_PORT, 'api_port': API_PORT,
              'db_password': secrets.token_hex(24), 'root_password': secrets.token_hex(24),
              'django_secret': secrets.token_urlsafe(48)}
    CONFIG_PATH.write_text(json.dumps(config, indent=2), encoding='utf-8')
    print('Created ignored local configuration; credentials are not printed.')


def start_database(config):
    if port_open(config['db_port']):
        root_connection(config).close()
        print('Project MySQL already running.')
        return
    binary = Path(config['mysql_binary'])
    data = STATE / 'mysql'
    common = [str(binary), '--no-defaults', f'--basedir={binary.parent.parent}', f'--datadir={data}']
    if not data.exists():
        print('Initializing project-only MySQL data directory...', flush=True)
        with (STATE / 'mysql-init.log').open('w', encoding='utf-8') as log:
            subprocess.run(common + ['--initialize-insecure'], stdout=log, stderr=log, check=True)
    elif not (data / 'mysql').exists():
        raise RuntimeError('Incomplete MySQL initialization. Inspect .local/mysql-init.log; no data was removed.')
    init = STATE / 'mysql-bootstrap.sql'
    init.write_text('\n'.join([
        f"ALTER USER 'root'@'localhost' IDENTIFIED BY '{config['root_password']}';",
        'CREATE DATABASE IF NOT EXISTS stylemate_local CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;',
        f"CREATE USER IF NOT EXISTS 'stylemate_local'@'localhost' IDENTIFIED BY '{config['db_password']}';",
        "GRANT ALL PRIVILEGES ON stylemate_local.* TO 'stylemate_local'@'localhost';",
        "GRANT ALL PRIVILEGES ON test_stylemate_local.* TO 'stylemate_local'@'localhost';",
    ]), encoding='utf-8')
    with (STATE / 'mysql.log').open('a', encoding='utf-8') as log:
        proc = subprocess.Popen(common + [f"--port={config['db_port']}", '--bind-address=127.0.0.1',
            '--mysqlx=0', '--skip-log-bin', '--innodb-buffer-pool-size=64M',
            f'--init-file={init}', '--console'], stdout=log, stderr=log, creationflags=FLAGS)
    (STATE / 'mysql.pid').write_text(str(proc.pid))
    try:
        for _ in range(60):
            if proc.poll() is not None:
                raise RuntimeError('MySQL startup failed. See .local/mysql.log.')
            try:
                root_connection(config).close()
                print(f"MySQL ready on 127.0.0.1:{config['db_port']}.")
                return
            except MySQLdb.Error:
                time.sleep(1)
        raise RuntimeError('MySQL startup timed out. See .local/mysql.log.')
    except Exception:
        proc.terminate()
        proc.wait(timeout=10)
        raise
    finally:
        init.unlink(missing_ok=True)


def managed_backend():
    pid_file = STATE / 'backend.pid'
    if not pid_file.exists():
        return None
    try:
        proc = psutil.Process(int(pid_file.read_text()))
        args = proc.cmdline()
        if str(MANAGE) in args and 'runserver' in args:
            return proc
    except (psutil.NoSuchProcess, psutil.AccessDenied, ValueError):
        pass
    return None


def health(config):
    with urlopen(f"http://127.0.0.1:{config['api_port']}/api/dev/health/", timeout=2) as response:
        return json.load(response)


def manage(*args):
    subprocess.run([sys.executable, str(MANAGE), *args], cwd=MANAGE.parent, check=True)


def start(config):
    start_database(config)
    manage('migrate', '--noinput')
    if port_open(config['api_port']):
        if managed_backend() and health(config).get('environment') == 'local-dev':
            print('Project backend already running.')
            return
        raise RuntimeError('API port is occupied by another process; it was not stopped.')
    with (STATE / 'backend.log').open('a', encoding='utf-8') as log:
        proc = subprocess.Popen([sys.executable, str(MANAGE), 'runserver',
            f"127.0.0.1:{config['api_port']}", '--noreload'], cwd=MANAGE.parent,
            stdout=log, stderr=log, creationflags=FLAGS)
    (STATE / 'backend.pid').write_text(str(proc.pid))
    for _ in range(30):
        if proc.poll() is not None:
            raise RuntimeError('Backend startup failed. See .local/backend.log.')
        try:
            if health(config)['status'] == 'ok':
                print(f"Backend ready: http://127.0.0.1:{config['api_port']}/api/dev/health/")
                return
        except OSError:
            pass
        time.sleep(1)
    proc.terminate()
    proc.wait(timeout=10)
    raise RuntimeError('Backend startup timed out. See .local/backend.log.')


def stop(config):
    proc = managed_backend()
    if proc:
        proc.terminate()
        proc.wait(timeout=10)
        print('Project backend stopped.')
    if port_open(config['db_port']):
        conn = root_connection(config)
        try:
            with conn.cursor() as cursor:
                cursor.execute('SHUTDOWN')
        finally:
            conn.close()
        for _ in range(30):
            if not port_open(config['db_port']):
                break
            time.sleep(1)
        else:
            raise RuntimeError('MySQL shutdown is still pending.')
        print('Project MySQL stopped; data retained.')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['setup', 'start', 'stop', 'status', 'test'])
    parser.add_argument('--mysql-bin')
    args = parser.parse_args()
    if args.action == 'setup':
        setup(args.mysql_bin)
        return
    config = load_config()
    if args.action == 'start':
        start(config)
    elif args.action == 'stop':
        stop(config)
    elif args.action == 'test':
        start_database(config)
        manage('check')
        manage('makemigrations', '--check', '--dry-run')
        manage('test', 'probe', '--noinput')
    else:
        try:
            root_connection(config).close()
            print('MySQL: ready (project data directory verified)')
        except MySQLdb.Error:
            print('MySQL: unavailable')
        try:
            print('Backend:', health(config)['status'])
        except OSError:
            print('Backend: unavailable')


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, subprocess.CalledProcessError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
