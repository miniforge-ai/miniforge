<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# refactor(gate): move every gate message into the gate catalog

**Tier:** standards / i18n
**Theme:** gates, message catalogs, Codex §7.4.3 gate

## Problem

The gate component already has `config/gate/messages/en-US.edn` and
`system.edn`. The stale-references, no-secrets, behavioral, opsv,
policy-pack and tests-pass gates read their text from those catalogs. The
rest did not. Their messages, registry descriptions and repair texts were
English literals in the source: the Codex consultation gate (both
verdicts and its description), syntax, lint, format, pre-verify lint,
plan / review / quality / release / policy, coverage, pre-commit
discipline, the mechanical capability descriptions, the registry's
unknown-gate and noop entries, and the interface's missing-repair
failure. 39 strings in all.

## Changes

- Each literal moves to a catalog key with the same text. Two were built
  with `str`, `:registry/unknown-gate {gate}` and
  `:coverage/below-threshold {coverage} {threshold}`. The translator
  substitutes `(str v)`, so the output matches the old `str` exactly,
  including the leading colon on a keyword gate name.
- User-facing text goes to `en-US.edn`. The one log line, lint's
  "Policy-pack check threw; lint gate fails closed", goes to `system.edn`
  through `system-t`, the same split `decide` uses.
- The one text change: pre-commit discipline's repair message was a
  multi-line literal, and the source indentation was part of the string.
  The catalog entry has the same lines without that indentation.
- `syntax`, `lint`, `format`, `pre-verify-lint`, `capabilities`,
  `codex-consultation`, `precommit-discipline`, `registry` and `interface`
  now require `ai.miniforge.gate.messages`. That namespace depends only on
  the messages component, so this adds no require cycle. `registry` gains
  its first require.
- New `messages_test.clj`. It scans the gate sources on the classpath for
  `(messages/t :k)`, `(msg/t :k)` and `(messages/system-t :k)` and asserts
  each key has an entry in the catalog that translator reads. A missing
  key does not throw: it renders as the bare key name, so a gate result
  saying "skipped" would read as a word rather than a defect. The test
  also asserts the scan found more than 30 uses, so an empty scan cannot
  pass vacuously.

The only literals left are inside rich-comment example blocks.

## Testing

Run here with Clojure 1.12 from Maven Central. Clojars is 403 through
this environment's proxy, so only namespaces without Clojars
dependencies could load:

- `messages-test`: 1 test, 80 assertions, pass.
- `codex-consultation-test`: 6 tests, 13 assertions, pass.
- `precommit-discipline-test`: 12 tests, 43 assertions, pass. It asserts
  on the moved message text.
- Every edited source file and both catalogs read cleanly.

Not run here: `registry`, `policy`, `lint`, `format`, `tests-pass`,
`capabilities` and `interface` tests. They need malli, babashka.fs or
slingshot from Clojars. CI runs them.
