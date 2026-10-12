"""Регрессии загрузчика: доверие, целостность, атомарность и сохранение версии."""

import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("updater", Path(__file__).with_name("component_updater.py"))
updater = importlib.util.module_from_spec(spec)
spec.loader.exec_module(updater)


def archive(entries):
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode="w:gz") as output:
        for name, data in entries.items():
            member = tarfile.TarInfo(name)
            member.size = len(data)
            output.addfile(member, io.BytesIO(data))
    return buffer.getvalue()


class UpdateTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.patch = patch.object(updater, "BASE", self.base)
        self.patch.start()
        self.addCleanup(self.patch.stop)

    def stage(self, release, destination):
        for index in range(7):
            updater.put(destination / (str(index) + ".py"), b"pass\n")
        return {"archiveSha256": "a" * 64}

    def run_update(self, validation=None):
        release = {"tag_name": "v1.11.3", "draft": False, "prerelease": False}
        with patch.object(updater, "installed_version", return_value="v1.11.2"), \
                patch.object(updater, "api", return_value=release), \
                patch.object(updater, "prepare_telegram", side_effect=self.stage), \
                patch.object(updater, "validate", side_effect=validation):
            updater.update("tg-ws-proxy")

    def test_url_trust_boundary(self):
        for url in ("http://github.com/a", "https://github.com.evil.test/a",
                    "https://evil.test/a", "https://user:pass@github.com/a",
                    "https://github.com:8443/a", "file:///etc/passwd"):
            with self.subTest(url=url), self.assertRaises(updater.UpdateError):
                updater.validate_url(url)
        updater.validate_url("https://release-assets.githubusercontent.com/abc")

    def test_archive_rejects_traversal(self):
        with self.assertRaises(updater.UpdateError):
            updater.archive_files(archive({"release/../../unsafe": b"bad"}))

    def test_archive_rejects_absolute_paths(self):
        with self.assertRaises(updater.UpdateError):
            updater.archive_files(archive({"/tmp/unsafe": b"bad"}))

    def test_manifest_detects_corruption(self):
        self.stage({}, self.base)
        updater.write_manifest(self.base)
        updater.verify_manifest(self.base)
        (self.base / "0.py").write_bytes(b"corrupt")
        with self.assertRaises(updater.UpdateError):
            updater.verify_manifest(self.base)

    def test_manifest_rejects_traversal(self):
        (self.base / "manifest.sha256").write_text(("a" * 64 + "  ../outside\n") * 7)
        with self.assertRaises(updater.UpdateError):
            updater.verify_manifest(self.base)

    def test_missing_digest_cannot_install_zapret(self):
        release = {"tag_name": "v1.0.6", "assets": [{"name": "zapret2-v1.0.6.tar.gz",
                   "browser_download_url": "https://github.com/bol-van/zapret2/releases/download/v1.0.6/zapret2-v1.0.6.tar.gz"}]}
        with self.assertRaises(updater.UpdateError):
            updater.prepare_zapret(release, self.base)

    def test_wrong_digest_cannot_install_zapret(self):
        release = {"tag_name": "v1.0.6", "assets": [{"name": "zapret2-v1.0.6.tar.gz",
                   "digest": "sha256:" + "0" * 64,
                   "browser_download_url": "https://github.com/bol-van/zapret2/releases/download/v1.0.6/zapret2-v1.0.6.tar.gz"}]}
        with patch.object(updater, "fetch", return_value=b"tampered"), self.assertRaises(updater.UpdateError):
            updater.prepare_zapret(release, self.base)

    def test_foreign_asset_url_cannot_install(self):
        release = {"tag_name": "v1.0.6", "assets": [{"name": "zapret2-v1.0.6.tar.gz",
                   "browser_download_url": "https://github.com/foreign/repo/asset"}]}
        with self.assertRaises(updater.UpdateError):
            updater.prepare_zapret(release, self.base)

    def test_success_publishes_complete_generation(self):
        self.run_update()
        active = self.base / "telegram"
        self.assertTrue(active.is_symlink())
        updater.verify_manifest(active)
        self.assertEqual(json.loads((active / "component.json").read_text())["version"], "v1.11.3")
        self.assertFalse(list(self.base.glob("stage-*")))

    def test_failed_validation_keeps_old_link(self):
        old = self.base / "old"
        old.mkdir()
        (self.base / "telegram").symlink_to("old")
        with self.assertRaises(updater.UpdateError):
            self.run_update(updater.UpdateError("incompatible"))
        self.assertEqual((self.base / "telegram").resolve(), old)
        self.assertFalse(list(self.base.glob("tg-ws-proxy-*")))

    def test_success_retains_previous_generation(self):
        (self.base / "old").mkdir()
        (self.base / "telegram").symlink_to("old")
        self.run_update()
        self.assertEqual((self.base / "telegram.previous").resolve(), self.base / "old")

    def test_downgrade_and_prerelease_are_skipped(self):
        for tag, prerelease in (("v1.11.1", False), ("v2.0.0", True)):
            with patch.object(updater, "installed_version", return_value="v1.11.2"), \
                    patch.object(updater, "api", return_value={"tag_name": tag, "draft": False, "prerelease": prerelease}), \
                    patch.object(updater, "prepare_telegram") as prepare:
                updater.update("tg-ws-proxy")
                prepare.assert_not_called()

    def test_git_blob_matches_git_object_format(self):
        self.assertEqual(updater.git_blob(b"hello\n"), "ce013625030ba8dba906f756967f9e9ca394464a")

    def test_telegram_rejects_modified_source(self):
        tree = {"tree": [
            {"path": "proxy/tg_ws_proxy.py", "type": "blob", "mode": "100644",
             "sha": updater.git_blob(b"original")},
            {"path": "LICENSE", "type": "blob", "mode": "100644",
             "sha": updater.git_blob(b"MIT")}]}
        data = archive({"release/proxy/tg_ws_proxy.py": b"modified", "release/LICENSE": b"MIT"})
        with patch.object(updater, "api", side_effect=[{"sha": "a" * 40}, tree]), \
                patch.object(updater, "fetch", return_value=data), self.assertRaises(updater.UpdateError):
            updater.prepare_telegram({"tag_name": "v1.11.3"}, self.base)

    def test_offline_failure_does_not_remove_active_link(self):
        (self.base / "old").mkdir()
        (self.base / "telegram").symlink_to("old")
        with patch.object(updater, "installed_version", return_value="v1.11.2"), \
                patch.object(updater, "api", side_effect=TimeoutError), self.assertRaises(TimeoutError):
            updater.update("tg-ws-proxy")
        self.assertTrue((self.base / "telegram").exists())

    def test_main_handles_both_upstreams_offline(self):
        with patch.object(updater.os, "geteuid", return_value=0), \
                patch.object(updater.signal, "signal"), \
                patch.object(updater.signal, "alarm"), \
                patch.object(updater, "update", side_effect=TimeoutError) as update:
            updater.main()
            self.assertEqual(update.call_count, 2)


if __name__ == "__main__":
    unittest.main()
