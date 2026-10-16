"""Installer regression tests only touch temporary IDE profiles."""

from pathlib import Path
from contextlib import redirect_stdout
import io
import json
import os
import tempfile
import unittest
import subprocess
import sys
from unittest.mock import patch
import xml.etree.ElementTree as ET

from privacy_guard import install as manager
from privacy_guard.common import atomic_write, property_value

ROOT = Path(__file__).resolve().parents[1]


class InstallerTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="guard-install-")
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name).resolve()
        self.config = self.base / "profile with spaces"
        self.config.mkdir()
        self.destination = self.base / "guard install"
        self.defaults = self.base / "default.vmoptions"
        self.defaults.write_text("-Xmx2g\n-Dfile.encoding=UTF-8\n")
        self.ide = {
            "name": "Fixture IDE", "version": "1", "profile": "Fixture",
            "config": str(self.config), "vmoptions": str(self.config / "fixture.vmoptions"),
            "default_vmoptions": str(self.defaults), "java_home": os.environ["JAVA_HOME"],
            "launcher": str(self.base / "fixture-launcher"), "ide_lib": str(self.base / "lib"),
            "plugin_roots": [str(self.base / "plugins")],
        }
        self.running_patch = patch.object(manager, "running", return_value=False)
        self.running_patch.start()
        self.addCleanup(self.running_patch.stop)
        self.output = io.StringIO()
        self.redirect = redirect_stdout(self.output)
        self.redirect.__enter__()
        self.addCleanup(self.redirect.__exit__, None, None, None)
        # The preflight JVM is covered by test_agent; use a deterministic local
        # report here to exercise installer transactions independently.
        def preflight(ide, jar, report):
            report.write_text("synthetic preflight\n")
        self.verify_patch = patch.object(manager, "verify", side_effect=preflight)
        self.verify_patch.start()
        self.addCleanup(self.verify_patch.stop)

    def install(self, dry_run=False):
        manager.install(self.ide, ROOT / "build/privacy-guard.jar", self.destination, dry_run)

    def test_dry_run_does_not_write(self):
        self.install(dry_run=True)
        self.assertFalse(self.destination.exists())
        self.assertEqual(list(self.config.iterdir()), [])
        self.assertTrue(json.loads(self.output.getvalue())["dry_run"])

    def test_existing_agents_and_other_settings_survive_install_and_rollback(self):
        vm = Path(self.ide["vmoptions"])
        before_vm = b"-Xmx3g\n-javaagent:/example/existing-agent.jar\n"
        vm.write_bytes(before_vm)
        props = self.config / "idea.properties"
        before_props = b"# original\ncustom.key=yes\nllm.jcp.analytics.enabled=true\n"
        props.write_bytes(before_props)
        self.install()
        self.assertIn(before_vm.decode(), vm.read_text())
        manager.remove(self.destination, rollback=True)
        self.assertEqual(vm.read_bytes(), before_vm)
        self.assertEqual(props.read_bytes(), before_props)
        self.assertFalse((self.config / "options/github-copilot.xml").exists())
        self.assertTrue((self.destination / "backup").exists())

    def test_seed_default_vm_options_when_custom_file_is_absent(self):
        self.install()
        vm = Path(self.ide["vmoptions"])
        self.assertTrue(vm.read_text().startswith(self.defaults.read_text()))
        manager.remove(self.destination, rollback=True)
        self.assertFalse(vm.exists())

    def test_rollback_preserves_later_edits(self):
        self.install()
        vm = Path(self.ide["vmoptions"])
        vm.write_text(vm.read_text() + "-Dmy.later.setting=true\n")
        props = self.config / "idea.properties"
        props.write_text(props.read_text().replace("otel.sdk.disabled=false", "otel.sdk.disabled=true") + "later.key=keep\n")
        xml = self.config / "options/github-copilot.xml"
        root = ET.fromstring(xml.read_bytes())
        component = root.find("./component")
        ET.SubElement(component, "option", {"name": "unrelated", "value": "keep"})
        xml.write_bytes(ET.tostring(root))
        manager.remove(self.destination, rollback=True)
        self.assertIn("-Dmy.later.setting=true", vm.read_text())
        self.assertNotIn("privacy-guard.jar", vm.read_text())
        self.assertIn("later.key=keep", props.read_text())
        self.assertIn('name="unrelated"', xml.read_text())

    def test_disable_persists_options_omitted_by_ide_defaults(self):
        self.install()
        xml = self.config / "options/github-copilot.xml"
        xml.write_text('<application><component name="github-copilot" /></application>')
        manager.remove(self.destination)
        root = ET.fromstring(xml.read_bytes())
        self.assertEqual(root.find(".//option[@name='enableTelemetry']").get("value"), "false")
        self.assertEqual(root.find(".//option[@name='fetchStrategy']").get("value"), "Client")
        self.assertNotIn("privacy-guard.jar", Path(self.ide["vmoptions"]).read_text())

    def test_running_ide_refuses_changes(self):
        with patch.object(manager, "running", return_value=True):
            with self.assertRaisesRegex(ValueError, "Close the IDE"):
                self.install()
        self.assertFalse(self.destination.exists())

    def test_duplicate_settings_refuse_before_mutation(self):
        props = self.config / "idea.properties"
        data = "otel.sdk.disabled=true\notel.sdk.disabled=false\n"
        props.write_text(data)
        with self.assertRaisesRegex(ValueError, "Duplicate property"):
            self.install()
        self.assertEqual(props.read_text(), data)
        self.assertFalse(self.destination.exists())

    def test_failed_write_restores_previous_files(self):
        one, two = self.base / "one", self.base / "two"
        one.write_bytes(b"original")
        def failing_write(path, data, mode=None):
            if path == two:
                raise OSError("simulated disk failure")
            atomic_write(path, data, mode)
        with patch.object(manager, "atomic_write", side_effect=failing_write):
            with self.assertRaisesRegex(OSError, "simulated"):
                manager.write_transaction({one: b"changed", two: b"new"})
        self.assertEqual(one.read_bytes(), b"original")
        self.assertFalse(two.exists())

    def test_symbolic_link_is_not_replaced(self):
        original, link = self.base / "original", self.base / "link"
        original.write_bytes(b"keep")
        link.symlink_to(original)
        with self.assertRaisesRegex(ValueError, "symbolic link"):
            atomic_write(link, b"changed")
        self.assertEqual(original.read_bytes(), b"keep")

    def test_second_install_preserves_existing_backups(self):
        self.install()
        state = (self.destination / "install.json").read_bytes()
        with self.assertRaisesRegex(ValueError, "already exists"):
            self.install()
        self.assertEqual((self.destination / "install.json").read_bytes(), state)

    def test_manual_changed_owned_value_survives_rollback(self):
        self.install()
        props = self.config / "idea.properties"
        props.write_text(props.read_text().replace("otel.sdk.disabled=true", "otel.sdk.disabled=custom"))
        manager.remove(self.destination, rollback=True)
        self.assertIn("otel.sdk.disabled=custom", props.read_text())

    def test_properties_escape_windows_like_paths(self):
        self.assertEqual(property_value("C:\\IDE\\plugins"), "C:\\\\IDE\\\\plugins")

    def test_cli_subcommands_parse_and_dispatch(self):
        for command in ["install", "verify", "status", "disable", "rollback"]:
            result = subprocess.run([sys.executable, str(ROOT / "guard.py"), command, "--help"], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
        with patch.object(manager, "resolve_ide", return_value=self.ide):
            manager.main(["verify", "--report", str(self.base / "cli-report.tsv")])
        self.assertTrue((self.base / "cli-report.tsv").exists())

    def test_mac_and_linux_product_info_layouts(self):
        for system, os_name, arch in [("Darwin", "macOS", "aarch64"), ("Linux", "Linux", "amd64")]:
            with self.subTest(system=system):
                home = self.base / ("Fixture.app" if system == "Darwin" else "linux-ide")
                info = home / "Contents/Resources/product-info.json" if system == "Darwin" else home / "product-info.json"
                info.parent.mkdir(parents=True)
                info.write_text(json.dumps({
                    "name": "Fixture", "version": "1", "dataDirectoryName": "Fixture2026.2",
                    "envVarBaseName": "FIXTURE_IDE",
                    "launch": [{"os": os_name, "arch": arch,
                                "launcherPath": "bin/fixture", "vmOptionsFilePath": "bin/fixture.vmoptions",
                                "javaExecutablePath": "jbr/bin/java"}],
                }))
                with patch.object(manager.platform, "system", return_value=system), patch.object(manager.platform, "machine", return_value=arch):
                    ide = manager.resolve_ide(home, self.config)
                self.assertEqual(ide["name"], "Fixture")
                self.assertEqual(ide["vmoptions"], str(self.config / "fixture.vmoptions"))
                self.assertEqual(ide["plugin_roots"][-1], str(home / "Contents/plugins" if system == "Darwin" else home / "plugins"))

    def test_transaction_rolls_back_deletions_on_failure(self):
        one, two = self.base / "one", self.base / "two"
        one.write_bytes(b"keep")
        def failing_write(path, data, mode=None):
            if path == two:
                raise OSError("simulated failure")
            atomic_write(path, data, mode)
        with patch.object(manager, "atomic_write", side_effect=failing_write):
            with self.assertRaises(OSError):
                manager.write_transaction({one: None, two: b"new"})
        self.assertEqual(one.read_bytes(), b"keep")

    def test_legacy_installation_is_not_modified(self):
        self.destination.mkdir()
        state = self.destination / "install.json"
        original = b'{"installed": "legacy"}'
        state.write_bytes(original)
        with self.assertRaisesRegex(ValueError, "different ledger"):
            manager.remove(self.destination)
        self.assertEqual(state.read_bytes(), original)
