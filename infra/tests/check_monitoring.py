"""Run promtool against the rules actually deployed by the ConfigMap (Python stdlib only)."""
import argparse
from pathlib import Path
import shutil
import subprocess
import tempfile
import textwrap


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--promtool', default='promtool')
    args = parser.parse_args()
    infra = Path(__file__).resolve().parents[1]
    manifest = (infra / 'monitoring/prometheus.yaml').read_text(encoding='utf-8')
    block = manifest.split('  rules.yml: |\n', 1)[1].split('\n---', 1)[0]
    with tempfile.TemporaryDirectory(prefix='stylemate-monitoring-') as directory:
        target = Path(directory)
        (target / 'rules.yml').write_text(textwrap.dedent(block), encoding='utf-8')
        shutil.copyfile(infra / 'tests/backup-alerts.test.yml', target / 'backup-alerts.test.yml')
        subprocess.run([args.promtool, 'check', 'rules', 'rules.yml'], cwd=target, check=True)
        subprocess.run([args.promtool, 'test', 'rules', 'backup-alerts.test.yml'], cwd=target, check=True)


if __name__ == '__main__':
    main()
