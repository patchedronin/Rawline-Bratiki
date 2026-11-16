import hashlib
import json
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import build
import upstream


class ApkStagingTest(unittest.TestCase):
    def fixture(self, folder, extra=None):
        apk = folder / "upstream.apk"
        entries = {"lib/arm64-v8a/libcarrier.so": b"carrier", "lib/arm64-v8a/librawline.so": b"do not reuse core",
                   "assets/carrier-licenses/arm64-v8a/Carrier-LICENSE": b"license",
                   "assets/carrier-licenses/x86_64/Carrier-LICENSE": b"other ABI",
                   "assets/routeprofiles/srslist.h": b"catalog", "assets/sb-dashboard/index.html": b"dashboard",
                   "assets/schema/sing-box.json": b"{}"}
        entries.update({f"lib/arm64-v8a/libcarrier{i}.so": b"carrier" for i in range(17)})
        if extra:
            entries.update(extra)
        with zipfile.ZipFile(apk, "w") as archive:
            for name, data in entries.items():
                archive.writestr(name, data)
        pin = {"repository": "ttrroyy/RawlineMobile", "tag": "rawline-1.1.4",
               "apk": {"name": "test.apk", "size": apk.stat().st_size, "sha256": build.digest(apk),
                       "url": "https://github.com/ttrroyy/RawlineMobile/releases/download/rawline-1.1.4/test.apk"},
               "signerSha256": "1234"}
        return apk, pin

    def test_only_pinned_carriers_and_runtime_assets_are_staged(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            apk, pin = self.fixture(root)
            verified = SimpleNamespace(stdout="Signer #1 certificate SHA-256 digest: 1234\n")
            with patch.object(build, "ROOT", root), patch.object(build, "load_pin", return_value=pin), \
                    patch.object(build.subprocess, "run", return_value=verified):
                build.stage(SimpleNamespace(apk=apk, apksigner=Path("apksigner")))
            self.assertEqual((root / "deployment/carriers/arm64-v8a/libcarrier.so").read_bytes(), b"carrier")
            self.assertFalse((root / "deployment/carriers/arm64-v8a/librawline.so").exists())
            self.assertFalse((root / "app/src/main/assets/carrier-licenses/x86_64").exists())

    def test_digest_tampering_is_rejected_before_apksigner(self):
        with tempfile.TemporaryDirectory() as temp:
            apk, pin = self.fixture(Path(temp))
            pin["apk"]["sha256"] = "0" * 64
            with patch.object(build, "load_pin", return_value=pin), patch.object(build.subprocess, "run") as runner:
                with self.assertRaisesRegex(ValueError, "SHA-256"):
                    build.stage(SimpleNamespace(apk=apk, apksigner=Path("apksigner")))
                runner.assert_not_called()

    def test_path_traversal_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            apk, pin = self.fixture(root, {"assets/schema/../../../../escape": b"malicious"})
            verified = SimpleNamespace(stdout="Signer #1 certificate SHA-256 digest: 1234\n")
            with patch.object(build, "ROOT", root), patch.object(build, "load_pin", return_value=pin), \
                    patch.object(build.subprocess, "run", return_value=verified):
                with self.assertRaisesRegex(ValueError, "Unsafe APK path"):
                    build.stage(SimpleNamespace(apk=apk, apksigner=Path("apksigner")))
            self.assertFalse((root / "escape").exists())

    def test_missing_carrier_blocks_staging(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            apk, pin = self.fixture(root)
            verified = SimpleNamespace(stdout="Signer #1 certificate SHA-256 digest: 1234\n")
            with patch.object(build, "ROOT", root), patch.object(build, "load_pin", return_value=pin), \
                    patch.object(build, "EXPECTED_NATIVE_LIBRARIES", 20), \
                    patch.object(build.subprocess, "run", return_value=verified):
                with self.assertRaisesRegex(ValueError, "carrier set changed"):
                    build.stage(SimpleNamespace(apk=apk, apksigner=Path("apksigner")))

    def test_wrong_signer_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            apk, pin = self.fixture(Path(temp))
            verified = SimpleNamespace(stdout="Signer #1 certificate SHA-256 digest: bad\n")
            with patch.object(build, "load_pin", return_value=pin), \
                    patch.object(build.subprocess, "run", return_value=verified):
                with self.assertRaisesRegex(ValueError, "signer"):
                    build.stage(SimpleNamespace(apk=apk, apksigner=Path("apksigner")))

    def test_arm32_payload_uses_reviewed_pin_and_is_not_arm64(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            apk, fixture_pin = self.fixture(root)
            with zipfile.ZipFile(apk) as old:
                entries = {n.replace('arm64-v8a', 'armeabi-v7a'): old.read(n) for n in old.namelist()}
            with zipfile.ZipFile(apk, 'w') as archive:
                for name, data in entries.items():
                    archive.writestr(name, data)
            abi_pin = {"name": "upstream-arm.apk", "size": apk.stat().st_size, "sha256": build.digest(apk),
                       "url": "https://github.com/ttrroyy/RawlineMobile/releases/download/rawline-1.1.4/upstream-arm.apk"}
            pin = {"repository": "ttrroyy/RawlineMobile", "tag": "rawline-1.1.4",
                   "apk": fixture_pin["apk"], "apks": {"armeabi-v7a": abi_pin}, "signerSha256": "1234"}
            verified = SimpleNamespace(stdout="Signer #1 certificate SHA-256 digest: 1234\n")
            with patch.object(build, 'ROOT', root), patch.object(build, 'load_pin', return_value=pin), \
                    patch.object(build.subprocess, 'run', return_value=verified):
                build.stage(SimpleNamespace(apk=apk, apksigner=Path('apksigner'), abi='armeabi-v7a'))
            self.assertTrue((root / 'deployment/carriers/armeabi-v7a/libcarrier.so').exists())
            self.assertFalse((root / 'deployment/carriers/arm64-v8a').exists())
            self.assertFalse((root / 'deployment/carriers/armeabi-v7a/librawline.so').exists())

    def test_unreviewed_abi_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Unsupported ABI'):
            build.selected_apk({}, 'x86')
        with self.assertRaisesRegex(ValueError, 'reviewed upstream pin'):
            build.selected_apk({}, 'armeabi-v7a')

    def test_arm32_cannot_reuse_arm64_digest(self):
        with tempfile.TemporaryDirectory() as temp:
            apk, pin = self.fixture(Path(temp))
            pin.update(repository='ttrroyy/RawlineMobile', tag='rawline-1.1.4', apks={
                'armeabi-v7a': {'name': 'arm.apk', 'size': apk.stat().st_size, 'sha256': '0' * 64,
                'url': 'https://github.com/ttrroyy/RawlineMobile/releases/download/rawline-1.1.4/arm.apk'}})
            with patch.object(build, 'load_pin', return_value=pin), patch.object(build.subprocess, 'run') as run:
                with self.assertRaisesRegex(ValueError, 'SHA-256'):
                    build.stage(SimpleNamespace(apk=apk, apksigner=Path('apksigner'), abi='armeabi-v7a'))
                run.assert_not_called()


class UpstreamMetadataTest(unittest.TestCase):
    def fixture(self, root):
        (root / "nb4a.properties").write_text("PACKAGE_NAME=app.rawline.mobile\nVERSION_NAME=1.2.0\nVERSION_CODE=111\nTHRONE_AAR_REF=" + "a" * 40 + "\n")
        asset = {"name": "upstream-arm64.apk", "size": 123, "sha256": hashlib.sha256(b"apk").hexdigest()}
        manifest = {"schema": 1, "packageName": "app.rawline.mobile", "versionName": "1.2.0", "versionCode": 111999,
                    "minSdk": 24, "coreRef": "a" * 40, "signerSha256": "b" * 64, "assets": {"arm64-v8a": asset}}
        item = {"tag_name": "rawline-1.2.0", "body": "<!-- rawline-update\n" + json.dumps(manifest) + "\n-->",
                "assets": [{"name": asset["name"], "size": asset["size"], "digest": "sha256:" + asset["sha256"],
                            "browser_download_url": "https://github.com/ttrroyy/RawlineMobile/releases/download/rawline-1.2.0/upstream-arm64.apk"}]}
        return item

    def test_pin_is_derived_only_from_consistent_source_and_release_metadata(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            item = self.fixture(root)
            with patch.object(upstream, "pin", return_value={"signerSha256": "b" * 64}):
                result = upstream.metadata(item, root, "c" * 40)
            self.assertEqual(result["commit"], "c" * 40)
            self.assertEqual(result["versionIndex"], 111)
            self.assertEqual(result["coreRef"], "a" * 40)

    def test_manifest_asset_digest_mismatch_blocks_update(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            item = self.fixture(root)
            item["assets"][0]["digest"] = "sha256:" + "0" * 64
            with patch.object(upstream, "pin", return_value={"signerSha256": "b" * 64}):
                with self.assertRaisesRegex(ValueError, "digest"):
                    upstream.metadata(item, root, "c" * 40)

    def test_protected_overrides_are_not_part_of_regenerated_patch(self):
        for name in ("README.md", ".gitignore", "bratiki/custom.patch", ".github/workflows/release.yml"):
            self.assertTrue(upstream.protected(name))
        self.assertFalse(upstream.protected("app/src/main/example.kt"))


if __name__ == "__main__":
    unittest.main()
