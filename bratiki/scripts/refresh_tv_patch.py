"""Update only the two reviewed TV preset sections of source-migration patch."""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
FILES = ['app/src/main/java/io/nekohasekai/sagernet/database/BratikiDefaults.kt',
         'app/src/androidTest/java/app/rawline/mobile/BratikiDefaultsTest.kt']
path = ROOT / 'bratiki/custom.patch'
raw = path.read_bytes().replace(b'\r\n', b'\n')
for name in FILES:
    header = f'diff --git a/{name} b/{name}\n'.encode()
    start = raw.index(header)
    end = raw.find(b'\ndiff --git ', start + len(header))
    end = len(raw) if end < 0 else end + 1
    replacement = subprocess.check_output(['git', 'diff', '--binary', '--full-index',
        '12295381f4a749c1555c56e0e4dbf9b44f4330be', '--', name], cwd=ROOT)
    replacement = replacement.replace(b'\r\n', b'\n')
    assert replacement.startswith(header)
    raw = raw[:start] + replacement + raw[end:]
path.write_bytes(raw)
print('Updated TV preset and test sections only')
