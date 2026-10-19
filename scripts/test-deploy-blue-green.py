#!/usr/bin/env python3
import importlib.util
from pathlib import Path
import tempfile
import io
import argparse
import json
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
    def test_only_exact_reviewed_receipt_table_expansion_is_allowed(self):
        old={'BOOT-INF/classes/db/migration/V1.sql':'old'}
        new=dict(old,**{r.COMMENT_RECEIPT_MIGRATION:r.COMMENT_RECEIPT_SHA256})
        self.assertFalse(r.compatible_migrations(old,new))
        self.assertTrue(r.compatible_migrations(old,new,True))
        self.assertFalse(r.compatible_migrations(new,old,True))
        self.assertFalse(r.compatible_migrations(old,dict(new,extra='hash'),True))
        self.assertFalse(r.compatible_migrations(old,dict(new,**{r.COMMENT_RECEIPT_MIGRATION:'changed'}),True))
        self.assertFalse(r.compatible_migrations(old,dict(new,**{'BOOT-INF/classes/db/migration/V1.sql':'changed'}),True))
        migration=Path(__file__).parent.parent/'src/main/resources/db/migration/V13__add_comment_create_requests.sql'
        self.assertEqual(r.digest(migration),r.COMMENT_RECEIPT_SHA256)

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
    def test_dependency_health_alone_does_not_satisfy_candidate_readiness(self):
        release=object.__new__(r.Release);release.port=18080
        with patch.object(r.urllib.request,'build_opener') as factory:
            factory.return_value.open.side_effect=[io.BytesIO(b'{"status":"UP"}'),io.BytesIO(b'{"status":"OUT_OF_SERVICE"}')]
            self.assertFalse(release.health(18080))
            factory.return_value.open.side_effect=[io.BytesIO(b'{"status":"UP"}'),io.BytesIO(b'{"status":"UP"}')]
            self.assertTrue(release.health(18080))
    def test_failed_syntax_restores_bind_mounted_inode_before_reload(self):
        with tempfile.TemporaryDirectory() as directory:
            release=object.__new__(r.Release);release.config=Path(directory)/'default.conf'
            release.config.write_text('original');inode=release.config.stat().st_ino
            release.write_config('new invalid configuration')
            release.write_config('original')
            self.assertEqual(release.config.stat().st_ino,inode)
            self.assertEqual(release.config.read_text(),'original')
    def rollback_fixture(self, directory, gated=True, changed_schema=False):
        root=Path(directory); (root/'releases'/'active').mkdir(parents=True)
        active=root/'releases'/'active'
        for path, gate, sql in [(root/'app.jar', True, 'SELECT 1'),(active/'previous.jar',gated,'SELECT 2' if changed_schema else 'SELECT 1')]:
            with zipfile.ZipFile(path,'w') as jar:
                jar.writestr('BOOT-INF/classes/db/migration/V1.sql',sql)
                if gate: jar.writestr('BOOT-INF/classes/com/ai/repo/release/ReleaseGateFilter.class',b'gate')
        (root/'active-release.json').write_text(json.dumps({'directory':str(active),'release':'b'*40,'candidateSha256':r.digest(root/'app.jar')}))
        (active/'previous-container.json').write_text(json.dumps([{'Config':{'Labels':{'com.logicoma.release':'a'*40}}}]))
        return root,argparse.Namespace(release=str(root/'releases'/'rollback'))

    def test_planned_rollback_uses_previous_artifact_and_never_restores_database(self):
        with tempfile.TemporaryDirectory() as directory:
            root,args=self.rollback_fixture(directory)
            r.prepare_rollback(args,root)
            self.assertEqual(args.revision,'a'*40)
            self.assertEqual(r.digest(Path(args.release)/'candidate.jar'),r.digest(root/'releases'/'active'/'previous.jar'))
            self.assertFalse(json.loads((Path(args.release)/'rollback-source.json').read_text())['databaseRestore'])
            self.assertEqual(json.loads((root/'active-release.json').read_text())['release'],'b'*40)

    def test_rollback_refuses_pre_gate_artifacts_before_creating_destination(self):
        with tempfile.TemporaryDirectory() as directory:
            root,args=self.rollback_fixture(directory,gated=False)
            with self.assertRaisesRegex(RuntimeError,'predates passive gate'):r.prepare_rollback(args,root)
            self.assertFalse(Path(args.release).exists())

    def test_rollback_refuses_schema_change_and_stale_current_artifact(self):
        with tempfile.TemporaryDirectory() as directory:
            root,args=self.rollback_fixture(directory,changed_schema=True)
            with self.assertRaisesRegex(RuntimeError,'changes schema'):r.prepare_rollback(args,root)
            self.assertFalse(Path(args.release).exists())
            (root/'app.jar').write_bytes(b'other release')
            with self.assertRaisesRegex(RuntimeError,'differs from release metadata'):r.prepare_rollback(args,root)

    def test_failed_candidate_never_enters_traffic_switch_and_calls_cleanup(self):
        release=object.__new__(r.Release)
        with patch.object(release,'preflight'),patch.object(release,'start_candidate',side_effect=RuntimeError('not ready')),patch.object(release,'switch') as switch,patch.object(release,'rollback') as rollback:
            with self.assertRaises(RuntimeError):release.deploy()
            switch.assert_not_called();rollback.assert_called_once()

if __name__=='__main__':unittest.main()
