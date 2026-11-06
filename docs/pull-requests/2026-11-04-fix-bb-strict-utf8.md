<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(ci): declare and test the Babashka the CLI needs

## Overview

`bb miniforge --help` failed on Babashka 1.12.204 before doing anything:
`Unable to resolve classname: java.nio.charset.CodingErrorAction` while loading
`execution-grant.store-read` (#2375). `bb.edn` declared a 1.3.0 minimum, and
every CI job ran the newest pinned Babashka (1.13.229), so nothing ever tested the
declared minimum.

The CLI needs Babashka 1.12.215: `CodingErrorAction` and `CharsetDecoder` arrived
in 1.12.209, and `clojure.tools.reader.reader-types/indexing-push-back-reader`,
which `compliance-scanner`'s exceptions-as-data reader uses, in 1.12.215.
`bb.edn` now declares 1.12.215, and a new CI job runs exactly that version.

## Motivation

Operators run the CLI on the Babashka they have installed. With a correct
`:min-bb-version`, an older Babashka prints
`WARNING: this project requires babashka 1.12.215 or newer` at startup instead of
leaving the operator with a bare class error. The issue accepts this route:
require a supported runtime and document the JVM path.

## Changes in Detail

1. `bb.edn`: `:min-bb-version "1.12.215"` (was 1.3.0).
2. CI job `babashka-minimum` installs the version `bb.edn` declares (read from
   `bb.edn`, so it cannot drift). It runs `bb check:bb-classpaths`, which loads
   every task's namespaces, and then `bb miniforge --help`. `main-status`
   includes it.
3. `docs/platform-support.md` states the minimum, what an older Babashka shows,
   the JVM entry point (`clojure -M:dev -m ai.miniforge.cli.main`), and how CI
   checks the minimum.

## How the minimum was found

1. The first push declared 1.12.204, the version from the issue, together with a
   Babashka-portable strict UTF-8 decoder. It replaced the four `CharsetDecoder`
   uses on the CLI's classpath.
2. The new job then failed on 1.12.204 in a second place:
   `Could not resolve symbol: rt/indexing-push-back-reader` in
   `compliance-scanner`. Babashka's own source shows that function arrived in
   1.12.215, released 2026-02-17.
3. Rather than rewrite working code for an older runtime, the minimum moved to
   1.12.215. That Babashka already has `CodingErrorAction`, so the decoder change
   was reverted: it only served versions the CLI no longer claims.

## Testing Plan

1. The `babashka-minimum` job on this PR is the test: it installs 1.12.215, loads
   every bb task, and starts the CLI.
2. With an unsatisfied `:min-bb-version`, Babashka 1.12.217 prints the warning and
   continues (checked with a scratch `bb.edn`).
3. `clojure -M:dev -m ai.miniforge.cli.main --help` starts on the JVM with a temp
   `MINIFORGE_HOME`.

## Deployment Plan

No migration. If the job finds another gap on 1.12.215, raise `:min-bb-version`
to the lowest version that passes.

## Related Issues/PRs

Fixes #2375.

## Checklist

- [x] The declared minimum is read by CI from `bb.edn`
- [ ] `babashka-minimum` CI job green on 1.12.215
- [ ] CI green, Copilot comments resolved
