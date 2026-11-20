"""Exercise the exact embedded secret-free release validator using mocked GitHub API.

The signing job stays source-free and retains environment approval.
"""
import ast
import base64
import hashlib
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[2]


def blocks():
    text = (ROOT / '.github/workflows/bratiki-release.yml').read_text()
    result = []
    for part in text.split("python3 - <<'PY'\n")[1:]:
        result.append('\n'.join(line[10:] for line in part.split('\n          PY', 1)[0].splitlines()))
    return result


class WorkflowContractTest(unittest.TestCase):
    def test_signing_source_is_parseable_and_keeps_approval(self):
        source = (ROOT / '.github/workflows/bratiki-release.yml').read_text()
        self.assertEqual(len(blocks()), 2)
        for block in blocks():
            ast.parse(block)
        self.assertIn('environment: release', source)
        self.assertNotIn('actions/checkout', source)
        self.assertIn("meta['abi'] == abi", source)
        self.assertIn("19 * len(expected_abis)", source)
        self.assertIn("assets = {target: asset for target in expected_abis}", source)
        self.assertIn("header[:5] == b'\\x7fELF'", source)
        self.assertIn("expected_signer", source)

    def validate(self, selected='armeabi-v7a', actual='armeabi-v7a', tamper=False):
        sha = 'a' * 40
        unsigned = b'fixture apk bytes'
        pin = json.loads((ROOT / 'bratiki/upstream.json').read_text())
        props = (ROOT / 'nb4a.properties').read_text()
        meta = {'schema': 1, 'repository': 'patchedronin/Rawline-Bratiki', 'sourceSha': sha,
                'runId': '123', 'runAttempt': '1', 'revision': 5, 'abi': actual,
                'packageName': 'app.rawline.mobile', 'versionName': pin['versionName'],
                'versionCode': pin['versionIndex'] * 1000 + 5, 'upstream': pin, 'minSdk': 24,
                'signerSha256': 'b3729dc9e7538e42f95e96b8f61c400ad9f4e39d309b23c7e4594043fd0e134c',
                'unsignedSize': len(unsigned), 'unsignedSha256': hashlib.sha256(unsigned).hexdigest()}
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, 'w') as z:
            z.writestr('build-metadata.json', json.dumps(meta))
            z.writestr(f'unsigned-{actual}.apk', unsigned + (b'tampered' if tamper else b''))
        archive = buf.getvalue()

        def response(req, **kwargs):
            url = req.full_url
            if url == 'https://fixture.invalid/artifact':
                return io.BytesIO(archive)
            if '/actions/runs/123/artifacts?' in url:
                obj = {'total_count': 1, 'artifacts': [{'name': 'bratiki-unsigned', 'expired': False,
                       'workflow_run': {'head_sha': sha}, 'size_in_bytes': len(archive),
                       'archive_download_url': 'https://fixture.invalid/artifact',
                       'digest': 'sha256:' + hashlib.sha256(archive).hexdigest()}]}
            elif url.endswith('/actions/runs/123'):
                obj = {'repository': {'full_name': meta['repository']},
                       'head_repository': {'full_name': meta['repository']}, 'head_branch': 'main',
                       'status': 'completed', 'conclusion': 'success', 'event': 'workflow_dispatch',
                       'workflow_id': 99, 'path': '.github/workflows/bratiki-build.yml',
                       'head_sha': sha, 'run_attempt': 1}
            elif url.endswith('/actions/workflows/99'):
                obj = {'path': '.github/workflows/bratiki-build.yml'}
            elif '/contents/' in url:
                value = json.dumps(pin) if '/upstream.json?' in url else props
                obj = {'content': base64.b64encode(value.encode()).decode()}
            else:
                raise AssertionError('Unexpected HTTP access: ' + url)
            return io.BytesIO(json.dumps(obj).encode())

        with tempfile.TemporaryDirectory() as temp, patch.dict(os.environ, {
            'REPOSITORY': meta['repository'], 'RUN_ID': '123', 'REVISION': '5',
            'GH_TOKEN': 'fixture', 'EXPECTED_ABI': selected}), \
             patch('urllib.request.urlopen', side_effect=response), \
             patch('urllib.request.OpenerDirector.open', side_effect=response):
            old = Path.cwd()
            try:
                os.chdir(temp)
                exec(compile(blocks()[0], 'release-validator', 'exec'), {})
                self.assertEqual((Path('validated') / f'unsigned-{actual}.apk').read_bytes(), unsigned)
            finally:
                os.chdir(old)

    def test_arm32_trusted_artifact_passes(self):
        self.validate()

    def test_universal_trusted_artifact_passes(self):
        self.validate('universal', 'universal')

    def test_arm64_trusted_artifact_still_passes(self):
        self.validate('arm64-v8a', 'arm64-v8a')

    def test_wrong_abi_is_rejected(self):
        with self.assertRaises(AssertionError):
            self.validate('armeabi-v7a', 'arm64-v8a')

    def test_tampered_apk_is_rejected(self):
        with self.assertRaises(AssertionError):
            self.validate(tamper=True)


if __name__ == '__main__':
    unittest.main()
