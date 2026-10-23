#!/usr/bin/env python3
"""Exercise g9.sh with sandbox paths and a fake JVM, without an ARM64 host."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


class G9PropertiesTest(unittest.TestCase):
    def test_local_properties_survive_temporary_gradle_home(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            home = root / "home"
            gradle = home / ".gradle"
            (gradle / "wrapper/dists").mkdir(parents=True)
            (gradle / "wrapper/dists/gradle-launcher-9.6.0.jar").touch()
            java = root / "jdk/bin"
            java.mkdir(parents=True)
            (java / "java").write_text(
                '#!/bin/sh\nprintf "%s\\n" "$@" >> "$GRADLE_USER_HOME/arguments"\n'
            )
            (java / "pkill").write_text("#!/bin/sh\nexit 0\n")
            for executable in java.iterdir():
                executable.chmod(0o755)
            temporary_home = root / "gradle-home"
            # Only replace host-specific filesystem locations; execute the wrapper's actual logic.
            script = (Path(__file__).resolve().parents[1] / "g9.sh").read_text()
            for old, new in {
                "/usr/lib/jvm/java-21-openjdk-arm64": str(java.parent),
                "/root/.gradle": str(gradle),
                "/dev/gh2": str(temporary_home),
                "/dev/gtmp2": str(root / "tmp"),
            }.items():
                script = script.replace(old, new)
            wrapper = root / "g9.sh"
            wrapper.write_text(script)
            environment = dict(os.environ, HOME=str(home))
            properties = gradle / "gradle.properties"
            subprocess.run(["sh", str(wrapper), "assembleDebug"], env=environment, check=True,
                           stdout=subprocess.DEVNULL)
            self.assertFalse((temporary_home / "gradle.properties").exists())
            # Exercise the cached-home path as well as initial setup.
            (temporary_home / "caches").mkdir()
            for path in ("/local/aapt2-wrapper", "/updated/aapt2-wrapper"):
                properties.write_text(f"android.aapt2FromMavenOverride={path}\n")
                subprocess.run(["sh", str(wrapper), "assembleDebug"], env=environment, check=True,
                               stdout=subprocess.DEVNULL)
                self.assertEqual(properties.read_text(), (temporary_home / "gradle.properties").read_text())
            properties.unlink()
            subprocess.run(["sh", str(wrapper), "assembleDebug",
                            "-Pandroid.aapt2FromMavenOverride=/explicit/aapt2"],
                           env=environment, check=True, stdout=subprocess.DEVNULL)
            self.assertFalse((temporary_home / "gradle.properties").exists())
            subprocess.run(["sh", str(wrapper), "assembleDebug"], env=environment, check=True,
                           stdout=subprocess.DEVNULL)
            self.assertFalse((temporary_home / "gradle.properties").exists())
            arguments = (temporary_home / "arguments").read_text().splitlines()
            self.assertIn("assembleDebug", arguments)
            self.assertIn("-Pandroid.aapt2FromMavenOverride=/explicit/aapt2", arguments)


if __name__ == "__main__":
    unittest.main()
