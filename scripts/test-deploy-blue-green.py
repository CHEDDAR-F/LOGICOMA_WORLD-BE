#!/usr/bin/env python3
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

spec=importlib.util.spec_from_file_location('release',Path(__file__).with_name('deploy-blue-green.py'))
r=importlib.util.module_from_spec(spec);spec.loader.exec_module(r)

class DeploymentSafety(unittest.TestCase):
    def test_only_production_routes_change_and_unknown_config_is_rejected(self):
        original='\n'.join(['proxy_pass http://172.17.0.1:8080;']*3+['proxy_pass http://172.17.0.1:8081;'])
        changed=r.route_config(original,8080,18080)
        self.assertEqual(changed.count(':18080;'),3)
        self.assertIn(':8081;',changed)
        with self.assertRaises(RuntimeError):r.route_config(original+'\nproxy_pass http://172.17.0.1:8080;',8080,18080)
    def test_migration_change_is_detected_even_for_same_filename(self):
        with tempfile.TemporaryDirectory() as directory:
            paths=[Path(directory)/n for n in ['old.jar','new.jar']]
            for path,data in zip(paths,['CREATE TABLE x(a INT);','DROP TABLE x;']):
                with zipfile.ZipFile(path,'w') as jar:
                    jar.writestr('BOOT-INF/classes/db/migration/V1.sql',data)
            self.assertNotEqual(r.migration_fingerprints(paths[0]),r.migration_fingerprints(paths[1]))
    def test_candidate_replaces_prior_activation_and_preserves_jvm_and_profile(self):
        command=r.candidate_command(['java','-Xmx320m','-Dlogicoma.release.activation-file=/old/active',
                                    '-jar','app.jar','--spring.profiles.active=prod','--server.port=18081'],
                                    Path('/new/candidate.jar'),Path('/new/active'),18080)
        self.assertIn('-Xmx320m',command)
        self.assertIn('--spring.profiles.active=prod',command)
        self.assertNotIn('-Dlogicoma.release.activation-file=/old/active',command)
        self.assertEqual(sum(x.startswith('--server.port=') for x in command),1)
        self.assertIn('--server.address=172.17.0.1',command)
        self.assertIn('--management.endpoint.health.probes.enabled=true',command)
    def test_failed_syntax_restores_bind_mounted_inode_before_reload(self):
        with tempfile.TemporaryDirectory() as directory:
            release=object.__new__(r.Release);release.config=Path(directory)/'default.conf'
            release.config.write_text('original');inode=release.config.stat().st_ino
            release.write_config('new invalid configuration')
            release.write_config('original')
            self.assertEqual(release.config.stat().st_ino,inode)
            self.assertEqual(release.config.read_text(),'original')
    def test_failed_candidate_never_enters_traffic_switch_and_calls_cleanup(self):
        release=object.__new__(r.Release)
        with patch.object(release,'preflight'),patch.object(release,'start_candidate',side_effect=RuntimeError('not ready')),patch.object(release,'switch') as switch,patch.object(release,'rollback') as rollback:
            with self.assertRaises(RuntimeError):release.deploy()
            switch.assert_not_called();rollback.assert_called_once()

if __name__=='__main__':unittest.main()
