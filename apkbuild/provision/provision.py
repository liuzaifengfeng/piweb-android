#!/usr/bin/env python3
"""Pi Web provisioning. No root; never overwrite an existing installation/config.
The APK authenticates the transport and pins this script + manifest by SHA-256.
"""
import argparse
import hashlib
import json
import os
import platform
import re
import secrets
import shutil
import socket
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.request
from pathlib import Path, PurePosixPath


class Cancelled(Exception):
    pass


def digest(file):
    h = hashlib.sha256()
    with open(file, 'rb') as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def validate_name(name):
    if not re.fullmatch(r'[A-Za-z][A-Za-z0-9_-]{0,39}', name):
        raise ValueError('目录名只允许字母开头，后接字母、数字、下划线或横线（最多 40 字符）')
    return name


def validate_ports(web, policy):
    if not all(1024 <= p <= 65535 for p in (web, policy)) or web == policy:
        raise ValueError('端口必须为 1024–65535，且两个端口不能相同')


def safe_extract(archive, dest, max_bytes):
    """Validate the ENTIRE archive before writing anything. Links are created last."""
    dest = Path(dest)
    with tarfile.open(archive, 'r:gz') as tar:
        members = tar.getmembers()
        if len(members) > 100000:
            raise ValueError('压缩包文件数超限')
        names, links, total = set(), [], 0
        for m in members:
            p = PurePosixPath(m.name)
            if p.is_absolute() or '..' in p.parts or not p.parts or '\\' in m.name:
                raise ValueError('压缩包包含非法路径')
            if str(p) in names:
                raise ValueError('压缩包包含重复路径')
            names.add(str(p))
            if not (m.isfile() or m.isdir() or m.issym()):
                raise ValueError('不允许硬链接、设备文件等特殊成员')
            total += m.size
            if total > max_bytes:
                raise ValueError('解压体积超限')
            if m.issym():
                if '\\' in m.linkname or PurePosixPath(m.linkname).is_absolute():
                    raise ValueError('非法符号链接')
                target = os.path.normpath(str(p.parent / m.linkname))
                if target == '..' or target.startswith('../'):
                    raise ValueError('符号链接越界')
                links.append((p, m.linkname))
        link_paths = {str(p) for p, _ in links}
        for m in members:
            if any(str(parent) in link_paths for parent in PurePosixPath(m.name).parents):
                raise ValueError('成员路径穿越符号链接')
        dest.mkdir(mode=0o700, parents=True, exist_ok=True)
        for m in members:
            out = dest / m.name
            if m.isdir():
                out.mkdir(mode=0o700, parents=True, exist_ok=True)
            elif m.isfile():
                out.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
                with tar.extractfile(m) as source, open(out, 'xb') as target:
                    shutil.copyfileobj(source, target)
                out.chmod(0o700 if m.mode & 0o111 else 0o600)
        for p, link in links:
            out = dest / p
            out.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
            out.symlink_to(link)
        for p, _ in links:
            try:
                (dest / p).resolve().relative_to(dest.resolve())
            except (ValueError, RuntimeError):
                raise ValueError('符号链接链越界或循环')


def exclusive_json(path, value):
    path = Path(path)
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    with open(path, 'x', opener=lambda p, flags: os.open(p, flags, 0o600)) as f:
        json.dump(value, f, ensure_ascii=False, indent=2)


def url_json(url, payload=None):
    body = None if payload is None else json.dumps(payload).encode()
    req = urllib.request.Request(url, data=body, headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.load(r)


class Installer:
    def __init__(self, args):
        self.args = args
        self.home = Path(os.environ['HOME']).resolve()
        self.target = self.home / validate_name(args.name)
        validate_ports(args.web_port, args.policy_port)
        self.root = None
        self.committed = False

    def status(self, phase, message, **extra):
        # No tokens, shell output, environment or credentials in human-readable logs.
        print(f'[{phase}] {message}', flush=True)
        if self.args.base:
            answer = url_json(self.args.base + '/status', {'phase': phase, 'message': message, **extra})
            if answer.get('cancelled') and phase not in ('failed', 'cancelled', 'ready', 'existing', 'missing'):
                raise Cancelled('用户取消；已完成的系统依赖安装不会回滚')

    def download(self, name, output, expected=None):
        if self.args.source_dir:
            shutil.copyfile(Path(self.args.source_dir) / name, output)
        else:
            # Download in chunks so cancellation is observed during large transfers.
            with urllib.request.urlopen(self.args.base + '/' + name, timeout=30) as response, open(output, 'xb') as f:
                checkpoint = time.monotonic()
                while True:
                    data = response.read(1024 * 1024)
                    if not data:
                        break
                    f.write(data)
                    if time.monotonic() - checkpoint > 2:
                        self.status('transfer', '正在传输安装资源')
                        checkpoint = time.monotonic()
        if expected and digest(output) != expected:
            raise ValueError(f'{name} 校验失败，未安装')

    def config_for_existing(self):
        if not (self.target / 'launch.sh').is_file() or not (self.target / 'node_modules/@agegr/pi-web/bin/pi-web.js').is_file():
            raise ValueError('该目录存在，但不是可识别的 Pi Web 安装；请选择其他目录名')
        config = self.target / 'deployment.json'
        if config.exists():
            c = json.loads(config.read_text())
            validate_ports(c['web_port'], c['policy_port'])
            if c.get('install_dir') != str(self.target) or c.get('schema') != 1:
                raise ValueError('现有部署配置不匹配')
            # Only return whitelisted configuration, never agent settings/auth files.
            return {k: c.get(k) for k in ('install_dir', 'web_port', 'policy_port', 'policy_token', 'version', 'managed')}
        if self.target.name != 'piweb' or (self.args.web_port, self.args.policy_port) != (30141, 30142):
            raise ValueError('旧版脚本仅支持 ~/piweb 和默认端口，不能在这里假装修改配置')
        return {'install_dir': str(self.target), 'web_port': 30141, 'policy_port': 30142, 'policy_token': '', 'version': 'legacy', 'managed': False}

    def dependencies(self):
        prefix = os.environ.get('PREFIX', '')
        if prefix != '/data/data/com.termux/files/usr' or platform.machine() not in ('aarch64', 'arm64'):
            raise ValueError('首版仅支持官方 com.termux 的 ARM64 环境')
        needed = [package for command, package in [('node', 'nodejs-lts'), ('curl', 'curl'), ('bash', 'bash'), ('git', 'git'), ('tar', 'tar')] if not shutil.which(command)]
        if needed:
            if self.args.no_packages:
                raise ValueError('缺少运行依赖：' + ', '.join(needed))
            self.status('dependencies', '从当前 Termux 官方软件源安装：' + ', '.join(needed))
            # Do not switch sources or upgrade all packages. Non-official sources require manual review.
            sources = list(Path(prefix, 'etc/apt').glob('sources.list')) + list(Path(prefix, 'etc/apt/sources.list.d').glob('*.list'))
            for file in sources:
                for line in file.read_text().splitlines():
                    line = line.strip()
                    if line.startswith('deb ') and not re.search(r'https://packages(?:-cf)?\.termux\.dev/', line):
                        raise ValueError('检测到未审核的软件源，请手动安装依赖后重试；安装器不会自动换源')
            log = self.root / 'packages.log'
            with open(log, 'w') as out:
                subprocess.run(['pkg', 'install', '-y', *needed], check=True, stdout=out, stderr=subprocess.STDOUT)
        v = subprocess.check_output(['node', '-p', 'process.versions.node'], text=True).strip()
        parts = tuple(int(n) for n in v.split('.')[:3])
        if parts < (22, 19, 0):
            raise ValueError('Node 必须 >=22.19；请自行确认升级，不自动替换现有 Node')

    def run(self):
        self.status('checking', '检查目录与安装环境')
        if self.args.action == 'probe':
            if self.target.exists():
                c = self.config_for_existing()
                self.status('existing', '发现现有安装；接管仅保存启动参数，不修改其文件', config=c)
            else:
                self.status('missing', '此目录未安装 Pi Web，可执行新安装')
            return
        if self.target.exists() or self.target.is_symlink():
            raise ValueError('目标已存在，拒绝覆盖。请接管现有安装或选择其他目录名')
        if platform.machine() not in ('aarch64', 'arm64'):
            raise ValueError('首版仅支持 ARM64')
        for p in (self.args.web_port, self.args.policy_port):
            with socket.socket() as s:
                try:
                    s.bind(('127.0.0.1', p))
                except OSError:
                    raise ValueError(f'端口 {p} 已被占用，请选择其他端口')
        self.root = Path(tempfile.mkdtemp(prefix='.piweb-provision-', dir=self.home))
        self.status('manifest', '读取并校验资源清单')
        manifest_file = self.root / 'manifest.json'
        self.download('manifest.json', manifest_file, self.args.manifest_sha)
        manifest = json.loads(manifest_file.read_text())
        if manifest['schema'] != 1 or manifest['arch'] != 'aarch64':
            raise ValueError('资源版本或架构不匹配')
        size = manifest['unpacked_bytes'] + manifest['files']['piweb.tar.gz']['size'] + 256 * 1024 * 1024
        if shutil.disk_usage(self.home).free < size:
            raise ValueError(f'空间不足，至少需要 {size // (1024*1024)} MiB 可用空间')
        self.dependencies()
        self.status('transfer', '传输 pi-web 固定版本资源')
        archive = self.root / 'piweb.tar.gz'
        self.download('piweb.tar.gz', archive, manifest['files']['piweb.tar.gz']['sha256'])
        self.status('extract', '校验压缩包路径并解压到独立暂存目录')
        stage = self.root / 'app'
        safe_extract(archive, stage, manifest['unpacked_bytes'])
        self.status('runtime', '验证 Node、Pi Web 与 PTY 原生模块')
        test = "const p=require('./node_modules/node-pty');const t=p.spawn(process.env.PREFIX+'/bin/sh',['-c','printf PIWEB_PTY_OK'],{cwd:process.cwd(),env:process.env});let s='';const timer=setTimeout(()=>{t.kill();process.exit(2)},5000);t.onData(x=>s+=x);t.onExit(e=>{clearTimeout(timer);process.exit(e.exitCode===0&&s.includes('PIWEB_PTY_OK')?0:1)});"
        try:
            subprocess.run(['node', '-e', test], cwd=stage, check=True, timeout=15, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
            raise ValueError('node-pty 在此设备加载/运行失败。未提交安装；请查看说明，明确授权后本机编译，不会自动安装编译工具链')
        version = json.loads((stage / 'node_modules/@agegr/pi-web/package.json').read_text())['version']
        if version != manifest['pi_web_version']:
            raise ValueError('Pi Web 版本与清单不一致')
        config = {'schema': 1, 'managed': True, 'version': manifest['version'], 'install_dir': str(self.target), 'web_port': self.args.web_port, 'policy_port': self.args.policy_port, 'agent_dir': str(self.home / '.pi/agent'), 'policy_token': secrets.token_hex(32)}
        exclusive_json(stage / 'deployment.json', config)
        exclusive_json(stage / '.piweb-managed.json', {'schema': 1, 'version': manifest['version']})
        # Never rename over any pre-existing entry (including a concurrent installer).
        # mkdir is the atomic reservation. Only our own empty directory is removed on reservation failure.
        self.status('commit', '部署独立安装目录；现有个人配置不会覆盖')
        self.target.mkdir(mode=0o700)
        self.committed = True
        for child in stage.iterdir():
            child.rename(self.target / child.name)
        warnings = []
        extensions = self.home / '.pi/agent/extensions'
        extensions.mkdir(parents=True, exist_ok=True, mode=0o700)
        for child in (self.target / 'bundled-extensions').iterdir():
            target = extensions / child.name
            if target.exists() or target.is_symlink():
                warnings.append(f'保留已有扩展 {child.name}')
                continue
            if child.is_dir():
                # Directory reservation prevents replacing an existing extension.
                try:
                    target.mkdir(mode=0o700)
                except FileExistsError:
                    warnings.append(f'保留已有扩展 {child.name}')
                    continue
                for file in child.iterdir():
                    shutil.copyfile(file, target / file.name)
            else:
                try:
                    with open(target, 'xb') as out, open(child, 'rb') as src:
                        shutil.copyfileobj(src, out)
                    target.chmod(0o600)
                except FileExistsError:
                    warnings.append(f'保留已有扩展 {child.name}')
        try:
            exclusive_json(self.home / '.pi/agent/permission-policy.json', {'level':'app', 'mode':'ask'})
        except FileExistsError:
            warnings.append('保留已有权限策略；未重置用户选择')
        if self.args.no_launch:
            self.status('installed', '文件和 PTY 验证通过，尚未启动服务', config=config, warnings=warnings)
            return
        self.status('starting', '启动并检查两个本机服务')
        env = dict(os.environ, PIWEB_NO_BROWSER='1')
        log = self.root / 'startup.log'
        with open(log, 'w') as out:
            subprocess.run([str(self.target / 'launch.sh')], env=env, stdout=out, stderr=subprocess.STDOUT, check=True, timeout=110)
        web = f'http://127.0.0.1:{config["web_port"]}/api/home'
        with urllib.request.urlopen(web, timeout=5) as r:
            if r.status != 200:
                raise ValueError('Web 自检失败')
        health = url_json(f'http://127.0.0.1:{config["policy_port"]}/health')
        if health.get('install_dir') != str(self.target):
            raise ValueError('策略端口被其他服务占用')
        self.status('ready', '安装完成；请在 Pi Web 中配置自己的模型账户', config=config, warnings=warnings)


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--base')
    p.add_argument('--source-dir', help='Local reviewed resource directory; for offline verification')
    p.add_argument('--manifest-sha')
    p.add_argument('--action', choices=['probe', 'install'], default='probe')
    p.add_argument('--name', default='piweb')
    p.add_argument('--web-port', type=int, default=30141)
    p.add_argument('--policy-port', type=int, default=30142)
    p.add_argument('--no-packages', action='store_true', help='Fail rather than installing missing dependencies')
    p.add_argument('--no-launch', action='store_true', help='Verify files and PTY only; do not start services')
    args = p.parse_args()
    if not args.base and not args.source_dir:
        p.error('--base or --source-dir required')
    if args.base and (not re.fullmatch(r'http://127\.0\.0\.1:[0-9]+/[a-f0-9]{64}', args.base) or not re.fullmatch(r'[a-f0-9]{64}', args.manifest_sha or '')):
        p.error('Invalid transport or missing pinned manifest hash')
    installer = None
    try:
        installer = Installer(args)
        installer.run()
    except Exception as e:
        cancelled = isinstance(e, Cancelled)
        message = str(e) if isinstance(e, (ValueError, Cancelled)) else type(e).__name__ + '：安装未完成，请查安装工作目录中的日志'
        if installer and installer.root:
            message += '；工作目录：' + str(installer.root)
            if installer.committed:
                message += '；新目录已创建但未完成自检，不会自动覆盖或删除；可检查日志后接管重试'
        try:
            if installer:
                installer.status('cancelled' if cancelled else 'failed', message)
        except Exception:
            print(message, file=sys.stderr)
        return 130 if cancelled else 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
