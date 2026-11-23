<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(bb-proc): correct the interface docstring and pin the stderr message

## Overview

Three corrections left after #1990 fixed `core/run!`. No behavior
change.

## Motivation

- The public `interface/run!` docstring said the command inherits
  stdio. `babashka.process/sh` captures stdout and stderr. That wrong
  claim misdirected the diagnosis of a run of daily-publish failures.
- One test assertion could not fail. It looked for `r2-said-no` in the
  exception message. The message always embeds the command vector, and
  the command contained that string.
- The test namespace docstring listed the commands the suite uses and
  left out `sleep`.

## Changes in detail

- `interface/run!` docstring states what the function does. It runs
  through `babashka.process/sh`, and throws on a non-zero exit with
  `:exit` and `:cmd` in the ex-data. `:err` is added only when stderr
  was captured as a string and is not blank.
- `test-run!-carries-captured-stderr-into-the-failure` builds its
  payload with `printf '%s-%s' r2 said-no`. The asserted string appears
  nowhere in the command vector. The `:err` check is now an equality.
- The test namespace docstring lists `sleep`.
- The stratum autofix added `^{:stratum n}` metadata and regrouped the
  test file under its layer headings. No test body changed apart from
  the one above.

## Testing plan

`ai.miniforge.bb-proc.core-test` runs 16 tests and 31 assertions with no
failures. With the stderr append removed from `core/run!`, the old test
still passed and the new test fails.

## Deployment plan

None.

## Related issues/PRs

Follows #1990. Replaces #1994, closed as superseded.

## Checklist

- [x] The hardened assertion fails when the behavior is removed
- [x] Merged with current `main`
- [ ] CI green on the final head
- [ ] Review threads resolved
