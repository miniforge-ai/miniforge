<!--
  Title: Split cli/main/commands/artifact_cmds.clj (rule 210)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(cli): split artifact_cmds.clj (rule 210)

## Overview

Splits the provenance-rendering spec and its renderer out of
`ai.miniforge.cli.main.commands.artifact-cmds` into a new sibling
namespace, `ai.miniforge.cli.main.commands.artifact-cmds.provenance-view`,
resolving a stratum-lint SL003 finding (the combined namespace measured
4 real layers, over the rule 210 budget of 3).

## Motivation

Part of the stratum-lint rule-210 remediation program's `bases/cli`
`main/commands/*.clj` batch (13 command-handler files being split
concurrently; each is an independent command module, low collision
risk with siblings).

`artifact_cmds.clj` mixed two concerns at four dependency-deep layers:
a provenance display spec/renderer chain (`keyword->str` ->
`provenance-spec` -> `display-provenance`) and the two command entry
points (`artifact-list-cmd`, `artifact-provenance-cmd`) sitting above
it. Extracting the display chain leaves the command file's own
dependency depth at 3 (infra helpers -> store/scan helpers -> the two
commands).

## Changes in Detail

- New file `artifact_cmds/provenance_view.clj`
  (`ai.miniforge.cli.main.commands.artifact-cmds.provenance-view`):
  `keyword->str` (Layer 0), `provenance-spec` (Layer 1),
  `display-provenance` (Layer 2) — unchanged behavior, 3 layers.
- `artifact_cmds.clj`: requires the new namespace as `provenance-view`
  and calls `provenance-view/display-provenance` in
  `artifact-provenance-cmd` where it previously called the local
  `display-provenance`. Drops to 3 layers (infra helpers; store/scan
  helpers; the two command entry points).

This is pure code motion: no logic changed, only relocated and
re-namespaced. Both moved private functions (`keyword->str`,
`provenance-spec`, `display-provenance`) had no callers outside this
file — confirmed via a fully-qualified-namespace grep
(`ai\.miniforge\.cli\.main\.commands\.artifact-cmds\b`) across
`components/`, `bases/`, and `projects/`; the only real callers are
`bases/cli/src/ai/miniforge/cli/main.clj` (registers the two unchanged
public command entry points) and the existing unit test, both of which
are untouched by this split.

## Testing Plan

- `stratum-lint` clean on both resulting files (exit 0, was SL003
  exit 1 on the original).
- `clojure -M:dev:test` direct run of
  `ai.miniforge.cli.main.commands.artifact-cmds-test`: 6 tests, 11
  assertions, 0 failures/errors.
- `clojure -M:dev:test` load of `ai.miniforge.cli.main` (the base's
  entry namespace, which requires `artifact-cmds`): loads clean.
- No `projects/miniforge/test/` references to this namespace (checked
  directly; bb test's change-scope would have skipped them).

## Deployment Plan

Merges to `main` immediately; no follow-up needed for this file.

## Related Issues/PRs

- Part of the stratum-lint rule-210 program's `bases/cli`
  `main/commands/*.clj` batch. Reference split for style:
  `components/policy-pack/src/ai/miniforge/policy_pack/builtin_detectors.clj`
  (miniforge#1730).

## Checklist

- [x] stratum-lint clean on both resulting files
- [x] Direct namespace test run green (`artifact-cmds-test`)
- [x] `ai.miniforge.cli.main` still loads (fan-in caller unaffected)
- [x] Adversarial self-review: def/defn set unchanged, only relocated
- [x] Zero fan-in confirmed repo-wide before starting (only `main.clj`
      + the existing unit test reference this namespace, both untouched)
