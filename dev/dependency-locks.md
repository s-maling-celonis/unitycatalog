# Dependency locks for SCA scanning

This fork commits `*.sbt.lock` files so that Cycode's Software Composition
Analysis scan can see transitive third-party dependencies, not just the ones
declared directly in `build.sbt` (CBE-56140). Without them the scan reports only
direct dependencies and the vulnerability picture is close to meaningless.

## Regenerating

**The locks describe a Linux x86-64 resolution, and must be generated on one.**
We ship a Linux container image, so that is the dependency set worth scanning —
and a few coordinates resolve differently per platform, so a lock written on
macOS will not match. Regenerate inside a container:

```bash
docker run --rm -u "$(id -u):$(id -g)" -e HOME=/tmp -v "$PWD":/repo -w /repo eclipse-temurin:17-jdk ./build/sbt dependencyLockWrite
```

Do this after any dependency change, and after every rebase or reconcile against
`upstream/main`. Then commit every changed `build.sbt.lock`. CI runs
`build/sbt dependencyLockCheck` on `ubuntu-latest` and fails if the committed
locks no longer match, so a lock generated on the wrong platform is caught rather
than shipped.

The root project aggregates all ten modules, so both tasks fan out to all of them
without any extra alias.

`build/sbt dependencyLockWrite` on a macOS host still works for inspecting what
resolves locally — just do not commit the result.

### Why the platform matters

`com.linecorp.armeria:armeria` (build.sbt) pulls in
`com.aayushatharva.brotli4j`, which selects its native binary through an
OS/architecture-activated Maven profile. A macOS arm64 host resolves
`native-osx-aarch64`; a Linux x86-64 host resolves `native-linux-x86_64`. The
version and the main `brotli4j` artifact are identical either way — only the
native shard differs. It is currently the only platform-variant coordinate in
the graph, but treat the container as the source of truth rather than assuming
that stays true.

## What lives where

`dependencyLockWrite` writes one lock per project, into that project's base
directory. All ten are committed:

| Lock | Module |
| --- | --- |
| `build.sbt.lock` | `root` (the aggregator itself — only the shared logging deps) |
| `server/build.sbt.lock` | `server` |
| `examples/cli/build.sbt.lock` | `cli` |
| `connectors/spark/build.sbt.lock` | `spark` |
| `connectors/hadoop/build.sbt.lock` | `hadoop` |
| `clients/java/build.sbt.lock` | `client` |
| `api/build.sbt.lock` | `apiDocs` |
| `dev/sca/controlapi/build.sbt.lock` | `controlApi` |
| `dev/sca/servermodels/build.sbt.lock` | `serverModels` |
| `dev/sca/controlmodels/build.sbt.lock` | `controlModels` |

The last three are based inside gitignored generated-output trees
(`target/control/java`, `server/target/models`, `server/target/controlmodels`),
so `scaLockUnder` in `build.sbt` redirects their locks under `dev/sca/`. Cycode
finds them there because it matches lock files by the `build.sbt.lock` filename
anywhere in the tree — see below.

## How Cycode reads these

Verified against [cycode-cli](https://github.com/cycodehq/cycode-cli) source, not
just the docs, because it determines whether per-module locks are worth
committing at all:

- File discovery keeps any file whose name ends with one of
  `SCA_CONFIGURATION_SCAN_SUPPORTED_FILES` (`cycode/cli/consts.py`), which
  includes `build.sbt.lock`. The match is a plain filename suffix test in
  `Excluder._is_file_extension_supported`, applied to every walked file. **There
  is no requirement that a lock sit next to a `build.sbt`,** so nested per-module
  locks are collected.
- The only path filter for SCA is `SCA_EXCLUDED_FOLDER_IN_PATH`
  (`node_modules`, `venv`, `.venv`, `__pycache__`, `.pytest_cache`, `.tox`,
  `.mvn`, `.gradle`, `.npm`, `.yarn`, `.bundle`, `.bloop`, `.build`,
  `.dart_tool`, `.pub`, `.uv`). None of the paths above contain any of those.
- The "restore" step (`RestoreSbtDependencies`) is a fallback for repositories
  that have *no* lock: it shells out to `sbt dependencyLockWrite --verbose`, reads
  the result and deletes it. Because our locks are committed,
  `verify_restore_file_already_exist` short-circuits it — the scanner reads the
  files as-is and never needs a working sbt or network on the scanning runner.

## Known limitations

- **One Spark variant only.** The `connectors/spark` graph depends on
  `-DsparkVersion`, and `deltaVersion` and `hadoopVersion` are likewise
  overridable. The committed locks describe the default from
  `project/spark-versions.json`.
- **Lock files carry a `timestamp` field**, so regeneration always produces a
  diff even when no dependency changed. `dependencyLockCheck` compares dependency
  sets and ignores it, but review diffs are noisier than necessary.
- **`excludeDependencies` is honoured, `dependencyOverrides` at consumer level is
  not modelled.** Each lock records what that module actually resolves, which is
  the right granularity for SCA: a module resolving an older transitive version
  than its sibling shows up as the older version, rather than being flattened to
  whichever version wins a build-wide resolution.
- **A stale `~/.m2` can break resolution locally.** `build/sbt` sets
  `-Dsbt.override.build.repos=true`, so the `resolvers` setting is ignored and
  only `build/sbt-config/repositories` applies — and that lists
  `maven-local: file://${user.home}/.m2/repository` ahead of the remote mirrors.
  If `~/.m2` holds a partially-downloaded coordinate (a pom and main jar but not
  the classified native jars), resolution binds to the local copy and fails
  instead of falling back to Central. Delete the offending directory under
  `~/.m2/repository` and re-run — `netty-transport-native-unix-common 4.2.7.Final`,
  which `connectors/spark` resolves, was the first case. CI runners start with an
  empty `~/.m2` and are not affected.
