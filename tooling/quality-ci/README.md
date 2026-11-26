# Synthetic quality-plugin CI staging

Portable staging for independent execution against the frozen root-auto-module integration.
The coordinator reviews and copies an explicit whitelist; this staging directory
is not a remote publication or a completed GitHub Actions run.

## Publication whitelist

| Staging | Public repository destination |
|---|---|
| Frozen portable plugin `nur-quality-plugin/`, `nur-detekt-rules/`, `nur-ktlint-rules/`, `config/` | `tooling/quality/` with the same four relative directories |
| This `run.py`, `fixture/`, `provenance.json`, README | `tooling/quality-ci/` |
| This `workflow.yml` | `.github/workflows/quality-pilot.yml` |

Exclude build outputs, IDE configuration, cached artifacts, signing material,
credentials, application sources/settings, real project reports and receipts.
The source bundle needs a separate coordinator whitelist review. This fixture
contains only newly written generic Kotlin and small synthetic API shapes.
Prefer file-level source selection: plugin `build.gradle.kts`, `settings.gradle.kts`,
`src/main/**`; architecture Kotlin files and native `META-INF/services/**`;
ktlint `src/main/**`; `config/profiles/nur-reference.editorconfig`. Include other
build inputs only when the frozen build explicitly requires them. Do not copy
entire working repositories or every profile/example merely to satisfy a directory.

## Intended verification

1. JDK21, Gradle9.4.1; downloaded distribution checked against the official SHA256.
2. Fresh private Gradle user home, public Maven dependencies, source-built plugin,
   native architecture rules and ktlint rules/config. No preloaded artifacts.
3. Two immutable plugin versions in a new filesystem Maven repository.
4. Root applies the plugin once; `:contracts`, `:ui`, `:domain` use Kotlin2.2.21,
   JVM11; UI/domain depend on the real compiled contracts output.
5. Four forbidden Screen parameters, one inferred cross-module DTO dependency,
   allowed callbacks/host/plain function/domain value remain clean.
6. Only consumer plugin version changes v1→v2→v1. v2 changes diagnostic bytecode,
   not policy; compare JAR/content/class hashes, messages and exact rollback.
7. Unresolved sentinel under suppression must fail the mandatory health guard,
   not merely fail Kotlin compilation. The consumer-only test injects it after
   a successful Kotlin producer compile, before analysis of live sources.
   This accommodates legitimate own-javac/Kotlin prerequisites without weakening
   the negative health assertion. Findings remain report-only.
8. A Java-only module is a blocking coverage gap, never a successful skip.
   Each healthy stage independently checks all three module identities, exact
   source hashes, provider/rules, zero health errors, channel acknowledgements
   and fresh nonces. Root coverage must remain auto, never manual.
9. `build --dry-run` must not contain architecture tasks. Actual compile producers
   are allowed; assemble/build lifecycle is not added to the analyzer.

Final task/report layout must be checked against the frozen root-auto contract.
The runner exposes `--architecture-task` and `--report-glob` for report layout,
but does not emulate auto module discovery using manual source/classpath injection.
No successful run is claimed until actual execution passes.

## Local / hosted invocation

```sh
python3 tooling/quality-ci/run.py \
  --quality-source tooling/quality \
  --gradle /path/to/gradle-9.4.1/bin/gradle \
  --work-dir /new/owned/run-directory
```

Or supply `GRADLE_BINARY`. Work directory must not exist. Generated dependencies,
distribution copy, Maven repository and consumer stay outside the source bundle.
Only `evidence/*.json`, `*.log`, `*.xml`, `*.health` are uploaded; never the
consumer/distribution/cache/repository. Evidence contains only synthetic inputs.

Workflow permissions are `contents: read`; no package publication, PAT, secrets,
application CI hooks, `pull_request_target` or deployment. Checkout credential
persistence is disabled. Concurrent runs cancel by workflow/ref; timeout40min.
Action commit pins and Gradle checksum were read from official sources; URLs and
values are in `provenance.json`. Push trigger is limited to `quality-pilot/**`
feature branches; PR and manual dispatch also work. No external actions are
authorized by preparing this staging folder; the coordinator owns publication.

## Limits

These are synthetic API shapes, not AndroidX/Compose runtime validation.
Filesystem Maven is not GitHub Packages authentication. A future green workflow
will not establish Android compilation, application behavior, Studio parity,
production CI rollout, team policy acceptance or human review.
