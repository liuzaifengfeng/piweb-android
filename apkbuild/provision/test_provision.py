import argparse
import importlib.util
import io
import json
import os
from pathlib import Path
import socket
import subprocess
import tarfile
import tempfile
import unittest
import urllib.request
from unittest.mock import patch

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('provision', ROOT / 'provision/provision.py')
p = importlib.util.module_from_spec(spec)
spec.loader.exec_module(p)


class ArchiveTests(unittest.TestCase):
    def archive(self, base, entries):
        file = Path(base) / 'test.tar.gz'
        with tarfile.open(file, 'w:gz') as tar:
            for name, kind, content in entries:
                m = tarfile.TarInfo(name)
                if kind == 'link':
                    m.type = tarfile.SYMTYPE
                    m.linkname = content
                    tar.addfile(m)
                elif kind == 'hardlink':
                    m.type = tarfile.LNKTYPE
                    m.linkname = content
                    tar.addfile(m)
                else:
                    m.size = len(content)
                    tar.addfile(m, io.BytesIO(content))
        return file

    def test_unsafe_paths_rejected_before_writes(self):
        for name, kind, value in [('../escape','file',b'bad'),('/absolute','file',b'bad'),('link','link','../../escape'),('hard','hardlink','a')]:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as d:
                archive=self.archive(d,[('good','file',b'ok'),(name,kind,value)])
                dest=Path(d)/'out'
                with self.assertRaises(ValueError):p.safe_extract(archive,dest,1000)
                self.assertFalse(dest.exists())

    def test_parent_symlink_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            a=self.archive(d,[('a','link','b'),('a/child','file',b'bad')])
            with self.assertRaises(ValueError):p.safe_extract(a,Path(d)/'out',100)

    def test_relative_symlink_allowed(self):
        with tempfile.TemporaryDirectory() as d:
            a=self.archive(d,[('node_modules/pkg/main','file',b'ok'),('node_modules/.bin/run','link','../pkg/main')])
            p.safe_extract(a,Path(d)/'out',100)
            self.assertEqual((Path(d)/'out/node_modules/.bin/run').read_bytes(),b'ok')

    def test_size_limit_and_duplicates(self):
        with tempfile.TemporaryDirectory() as d:
            a=self.archive(d,[('same','file',b'123'),('same','file',b'456')])
            with self.assertRaises(ValueError):p.safe_extract(a,Path(d)/'out',100)
            a=self.archive(d,[('large','file',b'12345')])
            with self.assertRaises(ValueError):p.safe_extract(a,Path(d)/'out',4)

    def test_exclusive_config(self):
        with tempfile.TemporaryDirectory() as d:
            file=Path(d)/'settings.json'
            p.exclusive_json(file,{'keep':'original'})
            with self.assertRaises(FileExistsError):p.exclusive_json(file,{'overwrite':True})
            self.assertEqual(json.loads(file.read_text()),{'keep':'original'})

    def test_input_validation(self):
        for name in ['../piweb','piweb;id','/tmp/x','piweb/x','piweb space','piweb\n']:
            with self.assertRaises(ValueError):p.validate_name(name)
        for ports in [(30141,30141),(1,30142),(65536,30142)]:
            with self.assertRaises(ValueError):p.validate_ports(*ports)


class FullInstallTest(unittest.TestCase):
    @unittest.skipUnless(os.environ.get('PIWEB_FULL_TEST') == '1', 'set PIWEB_FULL_TEST=1 for isolated real payload and HTTP/PTY tests')
    def test_fresh_install_and_preservation(self):
        # Only this newly allocated test HOME is changed; the live HOME and services are untouched.
        parent=ROOT/'test-work'
        parent.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='isolated-',dir=parent) as d:
            home=Path(d)
            agent=home/'.pi/agent'
            agent.mkdir(parents=True)
            (agent/'settings.json').write_text('{"keep":"user-settings"}')
            (agent/'auth.json').write_text('{"dummy":"LOCAL_TEST_ONLY"}')
            (agent/'extensions').mkdir()
            (agent/'extensions/permission-gate.ts').write_text('// user extension, preserve me')
            before={f: (agent/f).read_bytes() for f in ['settings.json','auth.json','extensions/permission-gate.ts']}
            def free_port():
                with socket.socket() as s:s.bind(('127.0.0.1',0));return s.getsockname()[1]
            web,policy=free_port(),free_port()
            while web==policy:policy=free_port()
            env=dict(os.environ,HOME=d,PIWEB_NO_WAKELOCK='1')
            command=['python',str(ROOT/'provision/provision.py'),'--source-dir',str(ROOT/'assets/provision'),'--action','install','--name','piweb-test','--web-port',str(web),'--policy-port',str(policy),'--no-packages']
            target=home/'piweb-test'
            try:
                run=subprocess.run(command,env=env,capture_output=True,text=True,timeout=180)
                self.assertEqual(run.returncode,0,run.stdout+run.stderr)
                self.assertIn('[ready]',run.stdout)
                self.assertEqual(urllib.request.urlopen(f'http://127.0.0.1:{web}/api/home').status,200)
                config=json.loads((target/'deployment.json').read_text())
                self.assertEqual(json.loads((agent/'permission-policy.json').read_text()),{'level':'app','mode':'ask'})
                for f,data in before.items():self.assertEqual((agent/f).read_bytes(),data)
                with self.assertRaises(urllib.error.HTTPError) as error:
                    urllib.request.urlopen(f'http://127.0.0.1:{policy}/policy')
                self.assertEqual(error.exception.code,401)
                error.exception.close()
                req=urllib.request.Request(f'http://127.0.0.1:{policy}/policy',headers={'Authorization':'Bearer '+config['policy_token']})
                self.assertEqual(json.load(urllib.request.urlopen(req)),{'level':'app','mode':'ask'})
                again=subprocess.run(command,env=env,capture_output=True,text=True,timeout=30)
                self.assertNotEqual(again.returncode,0)
                self.assertIn('拒绝覆盖',again.stdout)
                # Probe/adopt never writes or resets config/policy.
                hashes={f:p.digest(target/f) for f in ['deployment.json','launch.sh']}
                subprocess.run(command[:command.index('--action')]+['--action','probe','--name','piweb-test','--web-port',str(web),'--policy-port',str(policy)],env=env,check=True,capture_output=True,timeout=30)
                for f,sha in hashes.items():self.assertEqual(p.digest(target/f),sha)
                print(f'Isolated HTTP/PTY/policy test passed on ports {web}/{policy}; no model calls')
            finally:
                if (target/'stop.sh').exists():
                    subprocess.run([str(target/'stop.sh')],env=env,check=True,timeout=20)
                    import time
                    time.sleep(2)


if __name__=='__main__':unittest.main(verbosity=2)
