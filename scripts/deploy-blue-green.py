#!/usr/bin/env python3
"""Run on AWS host as root. Same-schema releases only; no parallel business writers."""
import argparse
import datetime
import fcntl
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import urllib.request
import zipfile


def run(*args, **kwargs):
    return subprocess.run(args, check=True, stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, **kwargs).stdout.decode().strip()


def digest(path):
    with open(path, 'rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def migration_fingerprints(path):
    with zipfile.ZipFile(path) as jar:
        return {name: hashlib.sha256(jar.read(name)).hexdigest() for name in jar.namelist()
                if name.startswith('BOOT-INF/classes/db/migration/') and name.endswith('.sql')}


def route_config(content, old_port, new_port):
    result, count = re.subn(r'http://172\.17\.0\.1:' + str(old_port) + r'(?=;)',
                            'http://172.17.0.1:' + str(new_port), content)
    if count != 3:
        raise RuntimeError('Expected exactly three production upstream routes; configuration changed')
    return result


def candidate_command(cmd, jar, marker, port):
    if cmd[0] != 'java' or '-jar' not in cmd:
        raise RuntimeError('Unsupported JVM command')
    at = cmd.index('-jar')
    jvm = [v for v in cmd[1:at] if not v.startswith('-Dlogicoma.release.activation-file=')]
    options = [v for v in cmd[at+2:] if not v.startswith(('--server.port=', '--server.address=',
                '--server.shutdown=', '--spring.lifecycle.timeout-per-shutdown-phase=',
                '--management.endpoint.health.probes.enabled='))]
    return ['java', *jvm, '-Dlogicoma.release.activation-file=' + str(marker), '-jar', str(jar),
            *options, '--server.port=' + str(port), '--server.address=172.17.0.1',
            '--server.shutdown=graceful', '--spring.lifecycle.timeout-per-shutdown-phase=15s',
            '--management.endpoint.health.probes.enabled=true']


class Release:
    def __init__(self, args):
        self.args = args
        self.root = Path('/opt/logicomanet-be')
        self.release = Path(args.release).resolve()
        if self.release.parent != self.root / 'releases' or not re.fullmatch(r'[A-Za-z0-9-]+', self.release.name):
            raise RuntimeError('Release must be a direct child of the releases directory')
        self.config = Path('/opt/logicomanet-fe/default.conf')
        self.marker = self.release / 'active'
        self.name = 'logicomanet-be-candidate-' + self.release.name.lower()
        self.previous = 'logicomanet-be-before-' + self.release.name.lower()
        self.routed = self.stopped = self.promoted = False
        self.created = False

    def event(self, phase):
        item = {'phase': phase, 'at': datetime.datetime.now(datetime.timezone.utc).isoformat()}
        with (self.release / 'events.jsonl').open('a') as stream:
            stream.write(json.dumps(item) + '\n'); stream.flush(); os.fsync(stream.fileno())
        print(json.dumps(item), flush=True)

    def health(self, port):
        try:
            paths=['/actuator/health']
            if port == self.port: paths.append('/actuator/health/readiness')
            opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
            for path in paths:
                with opener.open('http://172.17.0.1:' + str(port) + path, timeout=2) as response:
                    if json.load(response).get('status') != 'UP': return False
            return True
        except Exception:
            return False

    def wait_health(self, port, seconds):
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            if self.health(port): return
            time.sleep(.3)
        raise RuntimeError('Readiness deadline exceeded')

    def preflight(self):
        if self.marker.exists() or (self.release / 'previous-container.json').exists():
            raise RuntimeError('Release directory already used')
        if digest(self.root / 'app.jar') != self.args.expected or digest(self.release / 'candidate.jar') != self.args.candidate:
            raise RuntimeError('Artifact fingerprint mismatch; possible concurrent release')
        self.old = json.loads(run('docker', 'inspect', 'logicomanet-be'))[0]
        c = self.old['Config']; h = self.old['HostConfig']
        if not self.old['State']['Running'] or h['NetworkMode'] != 'host' or h.get('Privileged'):
            raise RuntimeError('Unsupported runtime')
        if h['Memory'] != 805306368 or h['MemorySwap'] != 1073741824:
            raise RuntimeError('Runtime capacity contract changed')
        if not any(bind.split(':')[0] == str(self.root) for bind in h['Binds']):
            raise RuntimeError('Release directory must be visible to JVM')
        if migration_fingerprints(self.root / 'app.jar') != migration_fingerprints(self.release / 'candidate.jar'):
            raise RuntimeError('Schema-changing release requires a separate compatibility review')
        with zipfile.ZipFile(self.release / 'candidate.jar') as jar:
            if 'BOOT-INF/classes/com/ai/repo/release/ReleaseGateFilter.class' not in jar.namelist():
                raise RuntimeError('Candidate lacks the passive release gate')
        available = int(re.search(r'MemAvailable:\s+(\d+)', Path('/proc/meminfo').read_text())[1])
        if available < 512 * 1024:
            raise RuntimeError('Insufficient memory headroom for candidate warmup')
        self.old_port = next((int(v.split('=', 1)[1]) for v in c['Cmd'] if v.startswith('--server.port=')), 8080)
        self.port = 18081 if self.old_port == 18080 else 18080
        import socket
        with socket.socket() as probe:
            probe.bind(('172.17.0.1', self.port))
        self.previous_metadata = (self.root/'active-release.json').read_bytes() if (self.root/'active-release.json').exists() else None
        self.original = self.config.read_text()
        self.new_config = route_config(self.original, self.old_port, self.port)
        self.wait_health(self.old_port, 5)
        (self.release / 'previous-container.json').write_text(json.dumps([self.old]))
        (self.release / 'previous-nginx.conf').write_text(self.original)
        shutil.copy2(self.root / 'app.jar', self.release / 'previous.jar')
        env = c['Env']
        if any('\n' in value for value in env): raise RuntimeError('Unsupported multiline runtime environment')
        (self.release / 'runtime.env').write_text('\n'.join(env) + '\n')
        self.backup_database()
        self.event('preflight-complete')

    def backup_database(self):
        directory = Path('/opt/backups'); directory.mkdir(exist_ok=True); directory.chmod(0o700)
        self.backup = directory / ('blue-green-' + self.release.name + '.sql.gz')
        with self.backup.open('xb') as destination:
            command = ['docker','exec','mysql','sh','-c',
                       'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --hex-blob logicoma_net']
            dump = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
            try:
                with gzip.GzipFile(fileobj=destination, mode='wb') as compressed:
                    shutil.copyfileobj(dump.stdout, compressed)
            finally:
                dump.stdout.close()
            if dump.wait() != 0: raise RuntimeError('Database backup failed')
        if self.backup.stat().st_size < 1024: raise RuntimeError('Database backup empty')
        with gzip.open(self.backup, 'rb') as stream:
            while stream.read(1024 * 1024): pass

    def start_candidate(self):
        cfg = self.old['Config']; h = self.old['HostConfig']
        args = ['docker','create','--name',self.name,'--network','host',
                '--restart',h['RestartPolicy']['Name'],'--memory',str(h['Memory']),
                '--memory-swap',str(h['MemorySwap']),'--env-file',str(self.release/'runtime.env'),
                '-w',cfg['WorkingDir']]
        for bind in h['Binds']: args += ['-v',bind]
        if h['LogConfig']['Type']: args += ['--log-driver', h['LogConfig']['Type']]
        for key,value in h['LogConfig']['Config'].items(): args += ['--log-opt', key+'='+value]
        for key,value in (cfg.get('Labels') or {}).items():
            if key != 'com.logicoma.release': args += ['--label',key+'='+value]
        args += ['--label','com.logicoma.release='+self.args.revision]
        if cfg.get('User'): args += ['--user',cfg['User']]
        args += [self.old['Image']] + candidate_command(cfg['Cmd'], self.release/'candidate.jar',self.marker,self.port)
        self.candidate_id = run(*args); self.created = True
        run('docker','start',self.name)
        self.wait_health(self.port,self.args.startup_timeout)
        if self.marker.exists(): raise RuntimeError('Candidate activated before traffic drain')
        run('docker','exec','nginx','nginx','-t')
        self.event('candidate-ready')

    def write_config(self, content):
        # Keep the bind-mounted file inode; replacing the file leaves nginx on its old inode.
        with self.config.open('w') as stream:
            stream.write(content); stream.flush(); os.fsync(stream.fileno())

    def nginx_workers(self):
        output = run('docker','top','nginx','-eo','pid,args')
        return {line.split()[0] for line in output.splitlines() if 'nginx: worker process' in line}

    def switch(self):
        if self.config.read_text() != self.original or digest(self.root/'app.jar') != self.args.expected:
            raise RuntimeError('Concurrent configuration or artifact change')
        old_workers = self.nginx_workers()
        if not old_workers: raise RuntimeError('No nginx worker generation found')
        self.routed = True
        self.write_config(self.new_config)
        # Roll back even when syntax validation or reload fails.
        run('docker','exec','nginx','nginx','-t')
        run('docker','exec','nginx','nginx','-s','reload')
        self.event('traffic-routed-passive')
        end = time.monotonic() + 20
        # Old workers finish their in-flight requests. New workers queue behind the passive gate.
        while old_workers & self.nginx_workers():
            if time.monotonic() >= end: raise RuntimeError('Old nginx workers did not drain')
            time.sleep(.1)
        run('docker','update','--restart=no',self.old['Id'])
        run('docker','stop','--time','15','logicomanet-be'); self.stopped = True
        run('docker','rename','logicomanet-be',self.previous)
        run('docker','rename',self.name,'logicomanet-be'); self.promoted = True
        # Old process is now stopped, including schedulers and process-local publication locks.
        self.event('old-writer-stopped')
        time.sleep(getattr(self.args, 'activation_delay', 0))
        self.marker.touch(exist_ok=False)
        self.event('candidate-active')
        self.wait_health(self.port, 5)
        # Same public read path as real clients, through the new nginx worker generation.
        for path, expected in [('/actuator/health',200),('/api/agents/me',401)]:
            code = run('curl','--silent','--output','/dev/null','--max-time','5','--write-out','%{http_code}',
                       '-H','Host: logicomanet.com','http://127.0.0.1'+path)
            if int(code) != expected: raise RuntimeError('Post-switch proxy probe failed')
        shutil.copy2(self.release/'candidate.jar',self.root/'app.jar')
        installer = self.root/'deploy-blue-green.py.next'
        shutil.copy2(Path(__file__),installer)
        installer.chmod(0o700)
        installer.replace(self.root/'deploy-blue-green.py')
        (self.root/'active-release.json').write_text(json.dumps({'release':self.args.revision,
            'port':self.port,'directory':str(self.release),'previousContainer':self.previous,
            'candidateSha256':self.args.candidate,'backup':str(self.backup),
            'operation':'rollback' if getattr(self.args,'rollback',False) else 'release'},indent=2))
        self.event('committed')
        print(json.dumps({'result':'healthy','release':self.args.revision,'port':self.port,
                          'previousContainer':self.previous,'databaseBackup':str(self.backup)}))

    def rollback(self):
        # Restore the old JVM before routing traffic back; do not replay any application request.
        if self.created:
            candidate = json.loads(run('docker','inspect',self.candidate_id))[0]
            if candidate['State']['Running']:
                run('docker','stop','--time','15',self.candidate_id)
            if candidate['Name'].lstrip('/') == 'logicomanet-be':
                run('docker','rename',self.candidate_id,self.name)
        run('docker','update','--restart='+self.old['HostConfig']['RestartPolicy']['Name'],self.old['Id'])
        old = json.loads(run('docker','inspect',self.old['Id']))[0]
        if not old['State']['Running']:
            shutil.copy2(self.release/'previous.jar',self.root/'app.jar')
            if old['Name'].lstrip('/') != 'logicomanet-be':
                run('docker','rename',self.old['Id'],'logicomanet-be')
            run('docker','start',self.old['Id'])
            self.wait_health(self.old_port,90)
        if self.routed:
            self.write_config(self.original)
            run('docker','exec','nginx','nginx','-t')
            run('docker','exec','nginx','nginx','-s','reload')
        if self.created:
            run('docker','rm','-f',self.candidate_id)
        metadata = self.root/'active-release.json'
        if self.previous_metadata is None:
            metadata.unlink(missing_ok=True)
        else:
            metadata.write_bytes(self.previous_metadata)

    def deploy(self):
        self.preflight()
        try:
            self.start_candidate()
            self.switch()
        except BaseException:
            self.rollback()
            raise


def prepare_rollback(args, root):
    """Prepare an ordinary passive handoff to the previous compatible artifact. Never restore DB."""
    root = root.resolve()
    destination = Path(args.release).resolve()
    if destination.parent != root / 'releases' or not re.fullmatch(r'[A-Za-z0-9-]+', destination.name):
        raise RuntimeError('Rollback requires a new direct release child')
    if destination.exists(): raise RuntimeError('Rollback destination already exists')
    active = json.loads((root / 'active-release.json').read_text())
    previous = Path(active['directory']).resolve()
    if previous.parent != root / 'releases': raise RuntimeError('Invalid active release directory')
    if digest(root/'app.jar') != active['candidateSha256']:
        raise RuntimeError('Active artifact differs from release metadata')
    artifact = previous / 'previous.jar'
    with zipfile.ZipFile(artifact) as jar:
        if 'BOOT-INF/classes/com/ai/repo/release/ReleaseGateFilter.class' not in jar.namelist():
            raise RuntimeError('Rollback target predates passive gate; requires separate recovery plan')
    if migration_fingerprints(root/'app.jar') != migration_fingerprints(artifact):
        raise RuntimeError('Rollback target changes schema; cannot restore live database')
    snapshot = json.loads((previous/'previous-container.json').read_text())[0]
    revision = (snapshot['Config'].get('Labels') or {}).get('com.logicoma.release')
    if not revision or not re.fullmatch(r'[0-9a-f]{40}', revision):
        raise RuntimeError('Rollback source revision missing')
    args.expected = active['candidateSha256']; args.candidate = digest(artifact); args.revision = revision
    destination.mkdir(mode=0o700)
    shutil.copy2(artifact,destination/'candidate.jar')
    (destination/'rollback-source.json').write_text(json.dumps({'from':active['release'],
        'to':revision,'targetSha256':args.candidate,'databaseRestore':False},indent=2))


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('release')
    for name in ['expected','candidate','revision']: parser.add_argument(name,nargs='?')
    parser.add_argument('--rollback',action='store_true',help='Warm and hand off to previous gated same-schema artifact; never restore DB')
    parser.add_argument('--startup-timeout',type=int,default=180)
    parser.add_argument('--activation-delay',type=float,default=0,
                        help='Root-only acceptance observation window, 0 to 3 seconds; requests remain passive')
    args=parser.parse_args()
    if not 0 <= args.activation_delay <= 3: parser.error('activation-delay must be between 0 and 3 seconds')
    if args.rollback and any([args.expected,args.candidate,args.revision]): parser.error('rollback derives fingerprints and source revision from active metadata')
    if not args.rollback and not all([args.expected,args.candidate,args.revision]): parser.error('release needs expected, candidate and revision')
    if os.geteuid()!=0: raise SystemExit('Run as root; runtime secrets remain on server')
    import signal
    def interrupted(signum, frame): raise SystemExit(128+signum)
    signal.signal(signal.SIGTERM,interrupted)
    signal.signal(signal.SIGHUP,interrupted)
    os.umask(0o077)
    locks=[]
    for path in ['/opt/logicomanet-be/deploy.lock','/opt/logicomanet-fe/deploy.lock']:
        lock=open(path,'a');fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB);locks.append(lock)
    try:
        if args.rollback: prepare_rollback(args,Path('/opt/logicomanet-be'))
        Release(args).deploy()
    except BaseException as error:
        print('Release failed safely: '+type(error).__name__+': '+str(error) if not isinstance(error,subprocess.CalledProcessError) else 'Release failed safely: subprocess error',flush=True)
        raise SystemExit(1)

if __name__=='__main__':main()
