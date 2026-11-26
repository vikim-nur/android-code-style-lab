"""Real public KSP2 fixture; no application data or exported classpath substitution."""
import base64
import hashlib
import json
from pathlib import Path
import shutil


def run_ksp(work, evidence, repository, version, execute):
    staging = Path(__file__).parent
    processor = work / "ksp-processor"
    shutil.copytree(staging / "ksp-processor", processor)
    execute("08-ksp-processor-build", processor, ["jar"])
    processor_jar = processor / "build/libs/synthetic-ksp-processor.jar"
    assert processor_jar.is_file()
    results = {}
    seen_nonces = set()

    def consumer(name, extra="", source=None):
        project = work / name
        shutil.copytree(staging / "ksp-fixture", project)
        settings = project / "settings.gradle"
        settings.write_text(settings.read_text().replace("@PILOT_REPOSITORY@", repository.as_uri()))
        build = project / "build.gradle"
        build.write_text(build.read_text().replace("@QUALITY_VERSION@", version))
        module = project / "generated/build.gradle"
        module.write_text(module.read_text().replace("@PROCESSOR_JAR@", processor_jar.as_uri()) + "\n" + extra)
        if source is not None:
            (project / "generated/src/main/kotlin/Use.kt").write_text(source)
        return project

    def run(label, project, success=True, pattern=None):
        log = execute(label, project, ["qualityArchitecture", "--info"], success, pattern)
        coverage_path = project / "build/reports/quality/architecture-coverage.json"
        assert coverage_path.is_file(), "KSP failures must leave coverage"
        coverage = json.loads(coverage_path.read_text())
        shutil.copyfile(coverage_path, evidence / f"{label}-coverage.json")
        edge = json.loads((project / "producer-edge.json").read_text())
        (evidence / f"{label}-edge.json").write_text(json.dumps(edge, indent=2) + "\n")
        assert ":generated:kspKotlin" in edge["direct"]
        assert not any(p.endswith((":assemble", ":build")) for p in edge["graph"])
        assert coverage["mode"] == "auto"
        module, = [m for m in coverage["modules"] if m["project"] == ":generated"]
        for index, path in enumerate(sorted(project.glob("**/build/reports/quality/*"))):
            if path.is_file() and path.suffix in (".xml", ".health", ".log"):
                shutil.copyfile(path, evidence / f"{label}-{index}{path.suffix}")
        if success:
            assert coverage["allModulesClaimed"] is True and module["status"] == "checked"
            report = Path(module["report"])
            rows = [line.split("\t") for line in report.with_suffix(".health").read_text().splitlines()]
            assert rows[0][:2] == ["NUR_HEALTH", "1"]
            nonce = rows[0][2]
            assert nonce == module["healthNonce"] and nonce not in seen_nonces
            seen_nonces.add(nonce)
            assert rows[1][0] == "PROVIDER"
            assert set(rows[1][2].split(",")) == {"ScreenForbiddenParameterType", "LayerForbiddenDependency"}
            files = [row for row in rows if row[0] == "FILE"]
            expected = {str(p.resolve()) for base in ("src/main/kotlin", "build/generated/ksp/main/kotlin")
                        for p in (project / "generated" / base).rglob("*.kt")}
            actual = set()
            for row in files:
                filename = base64.urlsafe_b64decode(row[1] + "=" * (-len(row[1]) % 4)).decode()
                assert filename not in actual
                actual.add(filename)
                assert hashlib.sha256(Path(filename).read_bytes()).hexdigest() == row[2]
                assert row[3:5] == ["0", "0"]
            assert actual == expected and len(files) == module["sources"]
            assert rows[-1] == ["END", str(len(files))]
            for channel in ("stdout", "stderr"):
                assert f"NUR_HEALTH_{channel.upper()}\t{nonce}" in report.with_suffix(f".{channel}.log").read_text()
        else:
            assert coverage["allModulesClaimed"] is False and module["status"] == "blocked"
        results[label] = {"module": module, "edge": edge, "exit_expected_success": success}
        # Save partial results as well, so a later assertion never erases evidence.
        (evidence / "ksp-results.json").write_text(json.dumps(results, indent=2) + "\n")
        return module, edge, log

    kotlin = consumer("ksp-kotlin")
    item, edge, _ = run("09-ksp-cold-kotlin-only", kotlin, pattern=r"Architecture HEALTHY")
    assert edge["classesExist"] is False
    adjustment, = item["classpathAdjustments"]
    assert adjustment["producerType"] == "com.google.devtools.ksp.gradle.KspAATask"
    assert adjustment["producerComplete"] and adjustment["registeredOutput"]
    assert adjustment["action"] == "omit-absent-after-success"
    assert not Path(adjustment["path"]).exists()
    assert len(adjustment["generatedKotlinSources"]) == 1 and item["sources"] == 2
    warm, _, _ = run("10-ksp-warm-kotlin-only", kotlin, pattern=r"Architecture HEALTHY")
    assert warm["classpathAdjustments"][0]["producerOutcome"] == "UP-TO-DATE"
    binary = consumer("ksp-binary", "ksp { arg('portable.mode', 'binary') }",
                      "package portable.use\nfun binary() = portable.generated.BinaryToken().value()\n")
    item, _, _ = run("11-ksp-binary-output", binary, pattern=r"Architecture HEALTHY")
    adjustment, = item["classpathAdjustments"]
    assert adjustment["action"] == "retain"
    binary_class = Path(adjustment["path"]) / "portable/generated/BinaryToken.class"
    assert binary_class.is_file() and int.from_bytes(binary_class.read_bytes()[6:8], "big") == 55
    for label, name, extra, source, pattern in (
        ("12-ksp-missing-required", "ksp-missing-required",
         "tasks.named('compileKotlin') { libraries.from(files('build/required-missing.jar')) }", None,
         r"required-missing|classpath|does not exist"),
        ("13-ksp-missing-neighbor", "ksp-neighbor",
         "tasks.named('compileKotlin') { libraries.from(files('build/generated/ksp/main/classes-neighbor')) }", None,
         r"classes-neighbor|classpath|does not exist"),
        ("14-ksp-producer-failed", "ksp-failed",
         "ksp { arg('portable.mode', 'fail') }", None, r"PORTABLE_KSP_PRODUCER_FAILED"),
        ("15-ksp-producer-disabled", "ksp-disabled",
         "tasks.matching { it.name == 'kspKotlin' }.configureEach { enabled = false }",
         "package portable.use\nfun plain() = \"synthetic\"\n", r"Architecture NOT CHECKED|Architecture NOT TRUSTED"),
        ("16-ksp-producer-skipped", "ksp-skipped",
         "tasks.matching { it.name == 'kspKotlin' }.configureEach { onlyIf { false } }",
         "package portable.use\nfun plain() = \"synthetic\"\n", r"Architecture NOT CHECKED|Architecture NOT TRUSTED"),
    ):
        project = consumer(name, extra, source)
        run(label, project, False, pattern)
    return {"passed": True, "ksp": "2.3.12", "results": results,
            "processor_sha256": hashlib.sha256(processor_jar.read_bytes()).hexdigest(),
            "synthetic_only": True}
