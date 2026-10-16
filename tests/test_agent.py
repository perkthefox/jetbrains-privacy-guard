"""Run the actual Java agent against original, synthetic plugin classes."""

from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest
import zipfile

from privacy_guard.common import java_tools, property_value

ROOT = Path(__file__).resolve().parents[1]


class AgentBehaviorTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix="privacy-guard-fixtures-")
        cls.base = Path(cls.temporary.name)
        cls.tools = java_tools(compiler=True)
        cls.classes = cls.base / "classes"
        sources = sorted((ROOT / "tests/fixtures/java").rglob("*.java"))
        subprocess.run([str(cls.tools["javac"]), "--release", "25", "-encoding", "UTF-8", "-d", str(cls.classes), *map(str, sources)], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def setUp(self):
        self.workspace = Path(tempfile.mkdtemp(prefix="profile with spaces-", dir=self.base))
        self.plugin_root = self.workspace / "custom plugin root"
        self.extra = self.plugin_root / "Extra Icons/lib/mock-new-version-2030.jar"
        self.copilot = self.plugin_root / "github-copilot-intellij/lib/mock-new-version-2030.jar"
        self.harness = self.workspace / "harness"
        self.harness.mkdir()
        shutil.copy2(self.classes / "TestMain.class", self.harness / "TestMain.class")
        for path in (self.classes / "com/intellij/ide/plugins/cl").glob("*.class"):
            target = self.harness / path.relative_to(self.classes)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, target)
        for path, copilot in [(self.extra, False), (self.copilot, True)]:
            path.parent.mkdir(parents=True)
            with zipfile.ZipFile(path, "w") as archive:
                for entry in self.classes.rglob("*.class"):
                    name = entry.relative_to(self.classes).as_posix()
                    if name in {"TestMain.class", "fixture/UnknownPluginFeature.class"} or name.startswith("com/intellij/ide/plugins/cl/"):
                        continue
                    if name.startswith("com/github/copilot/") == copilot:
                        archive.write(entry, name)
        self.unknown = self.workspace / "unknown.jar"
        with zipfile.ZipFile(self.unknown, "w") as archive:
            archive.write(self.classes / "fixture/UnknownPluginFeature.class", "fixture/UnknownPluginFeature.class")
        self.config = self.workspace / "guard.properties"
        self.config.write_text(
            "plugin.roots=" + property_value(str(self.plugin_root)) + "\n"
            + "log=" + property_value(str(self.workspace / "guard.log")) + "\n", encoding="utf-8")

    def test_behavior_and_unknown_hot_update(self):
        result = subprocess.run([
            str(self.tools["java"]), "-Dprivacy.fixture=present",
            f"-javaagent:{ROOT / 'build/privacy-guard.jar'}={self.config}",
            "-cp", os.pathsep.join(map(str, [self.extra, self.copilot, self.harness])), "TestMain",
            str(self.unknown), str(self.extra), str(self.copilot),
        ], capture_output=True, text=True, timeout=60)
        log = (self.workspace / "guard.log").read_text()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr + log)
        self.assertIn("PASS unrecognized hot update blocked", result.stdout)
        self.assertIn("PASS optional HTTP endpoints blocked", result.stdout)
        self.assertIn("PASS IntelliJ-style null-CodeSource", result.stdout)
        self.assertIn("BLOCKED_PLUGIN", log)
        self.assertNotIn("privacy.fixture=present", log)

    def test_offline_verifier_and_relative_report_path(self):
        result = subprocess.run([
            str(self.tools["java"]), "-jar", str(ROOT / "build/privacy-guard.jar"),
            "--verify", "verification.tsv", "--ide-lib", str(self.workspace / "lib"),
            str(self.plugin_root),
        ], cwd=self.workspace, capture_output=True, text=True, timeout=60)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("blocked=[]", result.stdout)
        self.assertTrue((self.workspace / "verification.tsv").stat().st_size)

    def test_unknown_schema_fails_offline_verification(self):
        shutil.copyfile(self.unknown, self.extra)
        result = subprocess.run([
            str(self.tools["java"]), "-jar", str(ROOT / "build/privacy-guard.jar"),
            "--verify", str(self.workspace / "verification.tsv"), str(self.plugin_root),
        ], capture_output=True, text=True, timeout=60)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Unrecognized plugin structure", result.stderr)
