<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(utf8): strict UTF-8 decoding that loads on the declared Babashka

## Overview

`bb miniforge --help` failed on Babashka 1.12.204 before doing anything:
`Unable to resolve classname: java.nio.charset.CodingErrorAction` while loading
`execution-grant.store-read` (#2375). Babashka added `CharsetDecoder`,
`CodingErrorAction` and `CharacterCodingException` together in 1.12.209.
`bb.edn` declared a 1.3.0 minimum, and CI only ever ran the newest pinned
Babashka, so nothing caught the gap.

Four namespaces on the CLI's classpath each built their own strict decoder.
They now share one, in a new `utf8` component, that uses only `String` and
`java.util.Arrays`. A new CI
job runs the declared minimum Babashka, so the minimum is now tested.

## Motivation

Operators run the CLI on the Babashka they have installed. Besides the
load-time failure, `evidence-bundle`'s file reader called `.newDecoder` at
runtime with no import. On an older Babashka its catch-all turned that error
into nil, and the evidence bundle read as absent without any error.

## Changes in Detail

1. `utf8` (new component): `decode-strict` returns the text, or nil when the
   bytes are not well-formed UTF-8. It decodes with the `String` constructor,
   which replaces a malformed sequence with U+FFFD. It accepts the result only
   when re-encoding gives back the same bytes. The first malformed position
   re-encodes as EF BF BD, which is itself well-formed, so malformed input can
   never round-trip.
2. The four strict decoders on bb task classpaths now call it. Each caller
   keeps its outcome for malformed input:
   1. `execution-grant.store-read/read-text` returns nil, and `checked-record`
      calls `on-error` (the `:fault` anomaly), as the caught exception did.
   2. `artifact.publication-codec/decode` returns nil. Publication reads still
      fail with `:fault :publication/read-failed`, and snapshots with
      `:invalid-input :snapshot/invalid`.
   3. `evidence-bundle.edn-file` returns nil, as before.
   4. `phase-opsv.boundary.bounded-io/utf8-text` still throws
      (`IllegalArgumentException`, matching `json-document`). Ingress catches
      every throw and maps it to `:unavailable`, as it did for the old
      `MalformedInputException`.
3. `bb.edn` declares `:min-bb-version "1.12.204"`, the version from the issue.
4. CI job `babashka-minimum` installs exactly that version. It runs
   `bb check:bb-classpaths`, which loads every task's namespaces, and then
   `bb miniforge --help`. `main-status` includes it.
5. `docs/platform-support.md` states the minimum and how CI checks it.
6. The `utf8` component is wired into the root dev and test aliases, four
   projects, two `bb.edn` task classpaths, and the four consumers' `deps.edn`.

Left out: `deliberation-harness.codec/strict-utf8` keeps its own `CharsetDecoder`.
That component is JVM-only and is on no bb task classpath. Its file spans 6
strata on main, and stratum-lint (SL003) refuses any edit to it until the
namespace is split.

## Testing Plan

1. `utf8.interface-test`: well-formed text decodes unchanged (empty, ASCII,
   two-, three- and four-byte forms, BOM, a literal U+FFFD). Each malformed
   class is rejected: lone continuation, invalid lead byte, truncation,
   overlong form, encoded surrogate, beyond U+10FFFF, and U+FFFD followed by a
   stray byte. 5000 seeded random byte strings decode exactly as the JDK's
   REPORT decoder does. The test passes under the JVM and under Babashka
   1.12.217.
2. Existing call-site regressions still pass: grant store, artifact
   publication, evidence-bundle file read, and opsv bounded I/O (now expecting
   `IllegalArgumentException`).
3. `bb check:bb-classpaths` loads every task, and `bb miniforge --help` starts
   under Babashka 1.12.217.
4. Static scan: no namespace on a bb task classpath imports a class that
   Babashka 1.12.204 lacks. The other classes missing there are resolved
   reflectively behind guards. The new CI job is the check that runs 1.12.204
   itself.

## Deployment Plan

No migration. If the `babashka-minimum` job finds another class 1.12.204 lacks,
fix that code or raise `:min-bb-version` to the lowest version that passes.

## Related Issues/PRs

Fixes #2375.

## Checklist

- [x] Malformed input is still rejected at every call site
- [x] Tests cover the decoder on both runtimes
- [ ] `babashka-minimum` CI job green
- [ ] CI green, Copilot comments resolved
