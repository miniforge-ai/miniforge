# refactor(semantic-analyzer): externalize scan limits to EDN

## Overview

Moves three operational tuning literals in the `semantic-analyzer`
component out of source code and into an EDN resource. Behavior is
unchanged; the values are identical to the prior inline literals.

## Motivation

Config-as-data (Dewey 007) asks that operational tuning values live in
data rather than as literals embedded in code. The component already
loads one EDN resource (the judge prompt), so the loader pattern exists
in-component. The file-selection and per-rule analysis limits were still
inline `def` literals.

## Changes

- Add `components/semantic-analyzer/resources/config/semantic-analyzer/limits.edn`
  with one non-namespaced map: `:max-file-size-bytes 50000`,
  `:max-files-per-rule 5`, `:default-rule-timeout-ms 300000`. Apache-2.0
  header included.
- `components/semantic-analyzer/src/ai/miniforge/semantic_analyzer/core.clj`:
  load the resource via `io/resource` + `slurp` + `edn/read-string` into a
  private `limits` def, mirroring the reliability component's loader. The
  three constants now read from `limits` with the original literals kept as
  named fallbacks.

No changes to function signatures or runtime behavior.

## Verification

- Load smoke under the workspace classpath: namespace loads;
  `max-file-size-bytes` = 50000, `max-files-per-rule` = 5,
  `default-rule-timeout-ms` = 300000 (identical to originals).
- `semantic-analyzer` component tests: 13 tests, 55 assertions, 0
  failures, 0 errors.
- `bb poly:check`: OK.
