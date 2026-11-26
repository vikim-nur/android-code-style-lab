"""Portable synthetic-only acceptance. Public Maven dependencies; no preloaded cache required."""
import argparse
import base64
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET
import zipfile


def digest(data):
    return hashlib.sha256(data).hexdigest()


def hashes(root):
    return {str(p.relative_to(root)): digest(p.read_bytes())
            for p in sorted(root.rglob("*")) if p.is_file()}


def jar_hash(path):
    with zipfile.ZipFile(path) as archive:
        entries = {n: digest(archive.read(n)) for n in sorted(archive.namelist())
                   if not n.endswith("/")}
    return {"archive": digest(path.read_bytes()), "content": digest(json.dumps(entries, sort_keys=True).encode()),
            "entries": entries}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--quality-source", type=Path, required=True)
    parser.add_argument("--work-dir", type=Path, required=True)
    parser.add_argument("--gradle", default=os.environ.get("GRADLE_BINARY"))
    parser.add_argument("--architecture-task", default="qualityArchitecture")
    parser.add_argument("--report-glob", default="**/build/reports/quality/*architecture*.xml")
    args = parser.parse_args()
    assert args.gradle, "Set GRADLE_BINARY or --gradle"
    gradle = str(Path(args.gradle).resolve())
    source = args.quality_source.resolve()
    work = args.work_dir.resolve()
    work.mkdir(parents=True, exist_ok=False)
    evidence = work / "evidence"
    evidence.mkdir()
    receipt = {"passed": False, "commands": [], "synthetic_only": True, "remote_publication": False,
               "limitations": ["Synthetic API shapes are not AndroidX or an Android build.",
                               "Filesystem Maven proves version delivery, not GitHub Packages.",
                               "No Studio, app runtime, production rollout or human review."]}
    env = dict(os.environ, GRADLE_USER_HOME=str(work / "gradle-home"))

    def execute(label, project, tasks, success=True, pattern=None):
        cmd = [gradle, "-p", str(project), "--console=plain", "--max-workers=2",
               "--no-configuration-cache", *tasks]
        item = {"label": label, "argv": cmd, "expected_success": success}
        receipt["commands"].append(item)
        start = time.monotonic()
        with (evidence / f"{label}.log").open("w") as stream:
            process = subprocess.run(cmd, env=env, stdout=stream, stderr=subprocess.STDOUT, timeout=900)
        log = (evidence / f"{label}.log").read_text()
        item.update(exit=process.returncode, seconds=round(time.monotonic() - start, 3))
        assert (process.returncode == 0) == success, f"{label}: unexpected exit, see evidence"
        if pattern:
            assert re.search(pattern, log), f"{label}: required evidence missing: {pattern}"
        return log

    try:
        version_log = execute("00-gradle-version", work, ["--version"])
        assert "Gradle 9.4.1" in version_log
        distribution = work / "distribution"
        distribution.mkdir()
        for name in ("nur-quality-plugin", "nur-detekt-rules", "nur-ktlint-rules", "config"):
            shutil.copytree(source / name, distribution / name,
                            ignore=shutil.ignore_patterns("build", ".gradle", ".kotlin"))
        receipt["source_manifest"] = hashes(distribution)
        plugin = distribution / "nur-quality-plugin"
        versions = plugin / "src/main/resources/quality/versions.properties"
        original = versions.read_text()
        release, = re.findall(r"(?m)^release=(.+)$", original)
        assert release.startswith("0.6."), "Requires frozen root-auto-all-modules bundle"
        receipt["source_release"] = release
        token = uuid.uuid4().hex[:12]
        v1, v2 = [f"0.6.0-ci.{token}.{n}" for n in (1, 2)]
        repository = plugin / "build/pilot-maven"

        def publish(version, label):
            versions.write_text(re.sub(r"(?m)^release=.*$", "release=" + version, original))
            execute(label, plugin, ["publishAllPublicationsToPilotLocalRepository"])

        publish(v1, "01-publish-v1")
        immutable_paths = sorted(p for p in repository.rglob(v1) if p.is_dir())
        assert len(immutable_paths) == 2
        immutable = {str(p): hashes(p) for p in immutable_paths}
        consumer = work / "consumer"
        shutil.copytree(Path(__file__).parent / "fixture", consumer)
        settings = consumer / "settings.gradle.kts"
        settings.write_text(settings.read_text().replace("@PILOT_REPOSITORY@", repository.as_uri()))
        build = consumer / "build.gradle.kts"
        template = build.read_text()
        assert template.count('id("kg.nur.quality")') == 1
        assert not any('id("kg.nur.quality")' in p.read_text()
                       for p in consumer.glob("*/build.gradle.kts"))
        fixture_before = {str(p.relative_to(consumer)): digest(p.read_bytes())
                          for p in consumer.rglob("*.kt")}
        previous_nonces = set()

        def coverage(label, healthy):
            path = consumer / "build/reports/quality/architecture-coverage.json"
            assert path.is_file(), "Root coverage receipt is mandatory even on failure"
            result = json.loads(path.read_text())
            shutil.copyfile(path, evidence / f"{label}-coverage.json")
            assert result["mode"] == "auto", "Manual sources/classpath must not bypass auto discovery"
            if healthy:
                assert result["allModulesClaimed"] is True and result["failures"] == []
                modules = {m["project"]: m for m in result["modules"]}
                assert set(modules) == {":", ":contracts", ":ui", ":domain"}
                assert modules[":"]["status"] == "not_module"
                for name in (":contracts", ":ui", ":domain"):
                    module = modules[name]
                    assert module["status"] == "checked" and module["compilation"] == "main"
                    report = Path(module["report"])
                    health = report.with_suffix(".health")
                    rows = [line.split("\t") for line in health.read_text().splitlines()]
                    assert rows[0][:2] == ["NUR_HEALTH", "1"]
                    nonce = rows[0][2]
                    assert nonce == module["healthNonce"] and nonce not in previous_nonces
                    previous_nonces.add(nonce)
                    assert rows[1][0] == "PROVIDER"
                    assert set(rows[1][2].split(",")) == {"ScreenForbiddenParameterType", "LayerForbiddenDependency"}
                    files = [row for row in rows if row[0] == "FILE"]
                    module_dir = consumer / name[1:]
                    expected = {str(p.resolve()) for p in (module_dir / "src/main/kotlin").rglob("*.kt")}
                    actual = set()
                    for row in files:
                        filename = base64.urlsafe_b64decode(row[1] + "=" * (-len(row[1]) % 4)).decode()
                        assert filename not in actual
                        actual.add(filename)
                        assert digest(Path(filename).read_bytes()) == row[2]
                        assert row[3:5] == ["0", "0"]
                    assert actual == expected and len(files) == module["sources"]
                    assert rows[-1] == ["END", str(len(files))]
                    assert f"NUR_HEALTH_STDOUT\t{nonce}" in report.with_suffix(".stdout.log").read_text()
                    assert f"NUR_HEALTH_STDERR\t{nonce}" in report.with_suffix(".stderr.log").read_text()
            else:
                assert result["allModulesClaimed"] is False
                assert result["failures"] or any(m["status"] in ("blocked", "unsupported") for m in result["modules"])
            return result

        def clear_reports():
            # Only generated regular report files in this newly created consumer.
            for directory in consumer.glob("**/build/reports/quality"):
                for path in directory.iterdir():
                    if path.is_file() and path.suffix in (".xml", ".health", ".log", ".json"):
                        path.unlink()

        def collect(label):
            reports = sorted(consumer.glob(args.report_glob))
            assert reports, "No module typed XML: adjust report contract after integration freeze"
            findings = []
            for index, path in enumerate(reports):
                shutil.copyfile(path, evidence / f"{label}-{index}.xml")
                for node in ET.parse(path).getroot():
                    findings.extend({"file": Path(node.attrib["name"]).name, **e.attrib}
                                    for e in node.findall("error"))
            for index, path in enumerate(sorted(consumer.glob("**/build/reports/quality/*"))):
                if path.is_file() and path.suffix in (".health", ".log"):
                    shutil.copyfile(path, evidence / f"{label}-channel-{index}{path.suffix}")
            return findings

        def inspect(version, label, changed_message):
            build.write_text(template.replace("@QUALITY_VERSION@", version))
            clear_reports()
            log = execute(label, consumer, [args.architecture_task, "--info"], pattern=r"Architecture HEALTHY")
            assert "--jvm-target 11" in log, "Automatic module target discovery must preserve JVM 11"
            cov = coverage(label, True)
            findings = collect(label)
            screen = [e for e in findings if e["source"].endswith(".ScreenForbiddenParameterType")]
            layer = [e for e in findings if e["source"].endswith(".LayerForbiddenDependency")]
            assert len(screen) == 4 and len(layer) == 1, findings
            assert {e["file"] for e in findings} == {"BadScreens.kt", "BadBoundary.kt"}
            assert all(("CI_V2_BYTECODE" in e["message"]) == changed_message for e in screen)
            jar = consumer / f".quality/rules/nur-architecture-rules-{version}.jar"
            packed, = repository.glob(f"kg/nur/quality/nur-quality-plugin/{version}/*.jar")
            with zipfile.ZipFile(packed) as archive:
                assert archive.read("quality/" + jar.name) == jar.read_bytes()
                style_name = f"nur-style-rules-{version}.jar"
                style = consumer / ".quality/rules" / style_name
                assert archive.read("quality/" + style_name) == style.read_bytes()
            with zipfile.ZipFile(jar) as archive:
                spi = sorted(n for n in archive.namelist()
                             if n.startswith("META-INF/services/") and not n.endswith("/"))
                assert spi == ["META-INF/services/dev.detekt.api.FileProcessListener",
                               "META-INF/services/dev.detekt.api.RuleSetProvider"]
            with zipfile.ZipFile(style) as archive:
                assert archive.read("META-INF/services/com.pinterest.ktlint.cli.ruleset.core.api.RuleSetProviderV3").strip()
            profiles = {name: digest((consumer / name).read_bytes()) for name in
                        (".quality/architecture.yml", ".quality/detekt.yml", ".editorconfig")}
            h = jar_hash(jar)
            receipt[label] = {"findings": findings, "jar": h, "profiles": profiles,
                              "style_jar_sha256": digest(style.read_bytes()), "coverage": cov}
            return findings, h, profiles

        f1, h1, p1 = inspect(v1, "02-v1-auto-multimodule", False)
        # Prove a real project-produced binary, rather than supplying fixture source as classpath.
        binary = consumer / "contracts/build/classes/kotlin/main"
        classes = list(binary.rglob("*.class"))
        assert classes and all(int.from_bytes(p.read_bytes()[6:8], "big") == 55 for p in classes)
        assert (binary / "META-INF").is_dir()
        receipt["producer_binary"] = {"class_count": len(classes), "major": 55,
                                      "hashes": hashes(binary), "declared_kotlin": "2.2.21"}
        rule = distribution / "nur-detekt-rules/ArchitectureRules.kt"
        text = rule.read_text()
        fragment = "Keep navigation in the host; pass callbacks to Screen/Content."
        assert text.count(fragment) == 1
        rule.write_text(text.replace(fragment, "CI_V2_BYTECODE: " + fragment))
        publish(v2, "03-publish-v2")
        f2, h2, p2 = inspect(v2, "04-v2-auto-multimodule", True)
        assert h1["archive"] != h2["archive"] and h1["content"] != h2["content"]
        changed = [n for n in h1["entries"] if h1["entries"][n] != h2["entries"].get(n)]
        assert len(changed) == 1 and changed[0].endswith("ScreenForbiddenParameterType.class")
        assert p1 == p2
        fr, hr, pr = inspect(v1, "05-rollback-auto-multimodule", False)
        assert (fr, hr, pr) == (f1, h1, p1)
        assert immutable == {str(p): hashes(p) for p in immutable_paths}
        lifecycle = execute("05b-no-lifecycle", consumer, ["build", "--dry-run"])
        assert not any(name in lifecycle for name in
                       ("qualityArchitectureModule", "qualityArchitectureCoverage", "qualityArchitectureManual"))
        broken = consumer / "ui/src/main/kotlin/Unresolved.kt"
        # Automatic mode legitimately materializes own javac/Kotlin prerequisites.
        # Inject AFTER a successful producer compile, so this tests analyzer health
        # rather than merely asking kotlinc to reject the unresolved input first.
        negative_source = '@file:Suppress("ALL")\npackage fixture.ui\nval unresolved: missing.Unknown = error("sentinel")\n'
        build.write_text(build.read_text() + '''
project(":ui") {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        tasks.named("compileKotlin") {
            doLast {
                project.file("src/main/kotlin/Unresolved.kt").writeText(
''' + json.dumps(negative_source) + '''
                )
            }
        }
    }
}
''')
        clear_reports()
        execute("06-health-failclosed", consumer, [args.architecture_task, "--rerun-tasks"], False,
                r"Architecture NOT TRUSTED.*compiler|Architecture NOT TRUSTED.*error")
        coverage("06-health-failclosed", False)
        collect("06-health-failclosed")
        # Keep negative fixture in evidence, not in source-preservation comparison.
        negative_hash = digest(broken.read_bytes())
        after = {str(p.relative_to(consumer)): digest(p.read_bytes())
                 for p in consumer.rglob("*.kt") if p != broken}
        assert after == fixture_before
        broken.unlink()
        build.write_text(template.replace("@QUALITY_VERSION@", v1))
        settings.write_text(settings.read_text() + '\ninclude(":javaOnly")\n')
        gap = consumer / "javaOnly"
        gap.mkdir()
        (gap / "build.gradle.kts").write_text("plugins { java }\n")
        clear_reports()
        execute("07-coverage-gap", consumer, [args.architecture_task], False,
                r"Architecture COVERAGE (?:GAP|INCOMPLETE)")
        gap_coverage = coverage("07-coverage-gap", False)
        assert any(m["project"] == ":javaOnly" and m["status"] == "unsupported"
                   for m in gap_coverage["modules"])
        collect("07-coverage-gap")
        receipt.update(passed=True, versions=[v1, v2], changed_class=changed,
                       immutable_versions=True, rollback_byte_exact=True,
                       unresolved_fixture_sha256=negative_hash, source_unchanged=True)
    except Exception as error:
        receipt["error"] = f"{type(error).__name__}: {error}"
        raise
    finally:
        (evidence / "verification.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print("PASS: synthetic root-auto multi-module Maven bytecode delivery and fail-closed health")


if __name__ == "__main__":
    main()
