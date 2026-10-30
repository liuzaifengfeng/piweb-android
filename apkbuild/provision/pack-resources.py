#!/usr/bin/env python3
"""Deterministic dependency-only payload. Never walk HOME, Pi settings or logs.
Consumes a pinned npm lock and installed package roots; refuses version drift.
No install scripts or network requests are executed.
"""
import gzip
import hashlib
import io
import json
import os
import sys
import tarfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCE = Path(os.environ.get('PIWEB_SOURCE', str(Path.home() / 'piweb'))).resolve()
OUT = ROOT / 'assets/provision'
OUT.mkdir(parents=True, exist_ok=True)
lock_bytes = (SOURCE / 'package-lock.json').read_bytes()
lock = json.loads(lock_bytes)
pi_version = lock['packages']['node_modules/@agegr/pi-web']['version']
if pi_version != '0.12.2':
    raise SystemExit('Review and update the pinned payload version before rebuilding')
files = {}


def add_tree(source, name):
    if name in files:
        return
    if source.is_symlink():
        target = os.readlink(source)
        if target.startswith('/') or os.path.normpath(str(Path(name).parent / target)).startswith('../'):
            raise ValueError(f'Unsafe symlink: {name}')
        files[name] = source
    elif source.is_file():
        files[name] = source
    elif source.is_dir():
        for child in sorted(source.iterdir()):
            if child.name in ('.git', '.cache', '__pycache__') or child.name.endswith('.log'):
                continue
            if name.endswith('/.next') and child.name in ('cache', 'trace', 'diagnostics'):
                continue
            child_name = name + '/' + child.name
            if child_name.startswith('node_modules/node-pty/build/') and not (
                child_name in ('node_modules/node-pty/build/Release', 'node_modules/node-pty/build/Release/pty.node', 'node_modules/node-pty/build/Release/spawn-helper')
            ):
                continue
            add_tree(child, child_name)


for name, meta in sorted(lock['packages'].items()):
    if not name:
        continue
    path = SOURCE / name
    if not path.exists():
        if meta.get('optional'):
            continue
        raise SystemExit(f'Missing locked package: {name}')
    actual = json.loads((path / 'package.json').read_text())
    if actual['version'] != meta['version']:
        raise SystemExit(f'Version mismatch: {name}')
    add_tree(path, name)
# npm CLI symlinks are not individual lock entries.
add_tree(SOURCE / 'node_modules/.bin', 'node_modules/.bin')
for child in sorted((ROOT / 'provision/runtime').iterdir()):
    add_tree(child, child.name)

virtual = {
    'package.json': json.dumps({'name':'piweb-portable', 'version':'1.0.0', 'private':True, 'dependencies':{'@agegr/pi-web':pi_version}}, indent=2).encode(),
    'package-lock.source.json': lock_bytes,
    'DISTRIBUTION-NOTICE.txt': b'Pi Web portable payload. Includes upstream package licenses in node_modules.\nOnly dependency files and reviewed launcher scripts are included; no personal Pi data.\nTermux, Node.js and account credentials are not included.\n',
}
size = sum(p.stat().st_size for p in files.values() if not p.is_symlink()) + sum(map(len, virtual.values()))
archive = OUT / 'piweb.tar.gz'
print(f'Packing {len(files)+len(virtual)} files, {size//1048576} MiB; fast compression enabled', flush=True)
packed_bytes = 0
next_progress = 50 * 1048576
with open(archive.with_suffix('.tmp'), 'wb') as output, gzip.GzipFile(filename='', mode='wb', fileobj=output, mtime=0, compresslevel=1) as gz, tarfile.open(fileobj=gz, mode='w') as tar:
    for name in sorted(set(files) | set(virtual)):
        if name in virtual:
            data = virtual[name]
            info = tarfile.TarInfo(name)
            info.size = len(data)
            info.mode = 0o600
            src = io.BytesIO(data)
        else:
            p = files[name]
            info = tar.gettarinfo(str(p), arcname=name)
            info.mode = 0o700 if (info.mode & 0o111) or name in ('launch.sh', 'stop.sh') else 0o600
            src = None if p.is_symlink() else open(p, 'rb')
        info.uid = info.gid = info.mtime = 0
        info.uname = info.gname = ''
        try:
            tar.addfile(info, src)
            packed_bytes += info.size
            if packed_bytes >= next_progress:
                print(f'Packed {packed_bytes//1048576}/{size//1048576} MiB', flush=True)
                next_progress += 50 * 1048576
        finally:
            if src:
                src.close()
archive.with_suffix('.tmp').replace(archive)
for name in ['bootstrap.sh', 'provision.py']:
    (OUT / name).write_bytes((ROOT / 'provision' / name).read_bytes())

def sha(file):
    h = hashlib.sha256()
    with open(file, 'rb') as f:
        for b in iter(lambda: f.read(1024*1024), b''):
            h.update(b)
    return h.hexdigest()

manifest = {
    'schema':1, 'version':'b1-piweb-' + pi_version, 'arch':'aarch64',
    'pi_web_version':pi_version, 'node_min':'22.19.0',
    'lock_sha256':hashlib.sha256(lock_bytes).hexdigest(),
    'unpacked_bytes':size,
    'files':{name:{'size':(OUT/name).stat().st_size, 'sha256':sha(OUT/name)} for name in ['bootstrap.sh','provision.py','piweb.tar.gz']},
}
(OUT / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
print(json.dumps({'archive_bytes':archive.stat().st_size, 'unpacked_bytes':size, 'members':len(files)+len(virtual)}, indent=2))
