#!/usr/bin/env python3
"""Fail-fast APK build. The installed/live service is never stopped.
Set PIWEB_KEYSTORE_PASS; the signing password is passed by environment, not argv.
"""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parent
PREFIX = Path(os.environ['PREFIX'])
if not os.environ.get('PIWEB_KEYSTORE_PASS'):
    raise SystemExit('Set PIWEB_KEYSTORE_PASS before signing')


def run(*cmd, **kwargs):
    display = list(map(str, cmd))
    print('+', ' '.join(display[:10]) + (f' ... ({len(display)} arguments)' if len(display)>10 else ''), flush=True)
    subprocess.run(list(map(str, cmd)), check=True, **kwargs)


def reusable_payload():
    try:
        assets = ROOT/'assets/provision'
        m = json.loads((assets/'manifest.json').read_text())
        if m['lock_sha256'] != hashlib.sha256((Path.home()/'piweb/package-lock.json').read_bytes()).hexdigest():
            return False
        for name, meta in m['files'].items():
            with open(assets/name, 'rb') as f:
                if hashlib.file_digest(f, 'sha256').hexdigest() != meta['sha256']: return False
        for name in ('bootstrap.sh', 'provision.py'):
            if (assets/name).read_bytes() != (ROOT/'provision'/name).read_bytes(): return False
        expected = {str(f.relative_to(ROOT/'provision/runtime')): f for f in (ROOT/'provision/runtime').rglob('*') if f.is_file()}
        with tarfile.open(assets/'piweb.tar.gz', 'r|gz') as tar:
            for entry in tar:
                if '/.next/cache/' in entry.name: return False
                if entry.name in expected:
                    if tar.extractfile(entry).read() != expected.pop(entry.name).read_bytes(): return False
        return not expected
    except (OSError, ValueError, KeyError, tarfile.TarError):
        return False

if '--refresh-payload' in sys.argv or not reusable_payload():
    run(PREFIX/'bin/python', ROOT/'provision/pack-resources.py')
else:
    print('Reusing verified payload (lock, script hashes and runtime snapshot match).', flush=True)
with tempfile.TemporaryDirectory(prefix='build-', dir=ROOT) as tmp:
    stage = Path(tmp)
    obj, dex = stage/'obj', stage/'dex'
    obj.mkdir(); dex.mkdir()
    base, signed = stage/'base.apk', stage/'signed.apk'
    run(PREFIX/'bin/aapt', 'package', '-f', '-M', ROOT/'AndroidManifest.xml', '-S', ROOT/'res', '-I', ROOT/'android.jar', '-F', base)
    run(PREFIX/'bin/javac', '-source', '8', '-target', '8', '-nowarn', '-bootclasspath', ROOT/'android.jar', '-d', obj, *sorted((ROOT/'src').rglob('*.java')))
    for cls in ['MainActivity','SetupActivity','ProvisionService','CommandResultReceiver','DeploymentConfig','ProvisionServer']:
        if not (obj/'com/piweb/launcher'/f'{cls}.class').is_file():
            raise SystemExit('Missing compiled class: '+cls)
    run(PREFIX/'bin/d8', '--lib', ROOT/'android.jar', '--min-api', '24', '--output', dex, *sorted(obj.rglob('*.class')))
    if not (dex/'classes.dex').is_file() or (dex/'classes.dex').stat().st_size < 10000:
        raise SystemExit('Missing/incomplete classes.dex')
    with zipfile.ZipFile(base, 'a', compression=zipfile.ZIP_DEFLATED) as apk:
        apk.write(dex/'classes.dex', 'classes.dex')
        # AAPT treats .gz assets specially. Insert the exact whitelist ourselves,
        # preserving names and bytes and excluding interrupted packer .tmp files.
        for name in ('bootstrap.sh', 'provision.py', 'manifest.json', 'piweb.tar.gz'):
            apk.write(ROOT/'assets/provision'/name, 'assets/provision/'+name,
                      compress_type=zipfile.ZIP_STORED if name.endswith('.gz') else zipfile.ZIP_DEFLATED)
    run(PREFIX/'bin/apksigner', 'sign', '--ks', ROOT/'piweb.jks', '--ks-pass', 'env:PIWEB_KEYSTORE_PASS', '--key-pass', 'env:PIWEB_KEYSTORE_PASS', '--min-sdk-version', '24', '--out', signed, base)
    run(PREFIX/'bin/apksigner', 'verify', '--verbose', signed)
    with zipfile.ZipFile(signed) as apk:
        manifest = json.loads(apk.read('assets/provision/manifest.json'))
        assert apk.getinfo('classes.dex').file_size > 10000
        for name, meta in manifest['files'].items():
            h = hashlib.sha256()
            with apk.open('assets/provision/'+name) as f:
                for chunk in iter(lambda: f.read(1048576), b''): h.update(chunk)
            if h.hexdigest() != meta['sha256']: raise SystemExit('APK asset hash mismatch: '+name)
    # Only a fully verified APK replaces the last successful output.
    signed.replace(ROOT/'PiWebLauncher.apk')
    idsig = Path(str(signed)+'.idsig')
    if idsig.exists(): idsig.replace(ROOT/'PiWebLauncher.apk.idsig')
print('APK ready:', ROOT/'PiWebLauncher.apk', (ROOT/'PiWebLauncher.apk').stat().st_size, 'bytes')
