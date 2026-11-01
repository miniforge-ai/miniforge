<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat(dev): clj-kondo types from Malli function schemas

## Overview

`bb lint:malli-types` turns every brick function's Malli schema
(`:malli/schema` var metadata or `m/=>`) into clj-kondo types at
`.clj-kondo/metosin/malli-types-clj/config.edn`, which clj-kondo loads with
no config change. A call whose literal arguments, or whose use of the return
value, contradict the schema is then a lint error in the editor and in
`bb lint:clj:all`. CI runs `bb lint:malli-types:check`, which fails when the
committed types differ from the current schemas.

Stacked on #2297, which declares the first schemas (the gate interface).

## Motivation

The gate PR (#2297) checks the gate interface's schemas in instrumented tests,
which catches a bad call only when a test runs it. clj-kondo catches the same
mistakes in calls no test reaches, at lint time: a gate named by string
(`Expected: keyword, received: string`), a single keyword where
`check-gates` wants a sequence, a boolean result used as a number.

## Changes in Detail

1. `development/src/ai/miniforge/malli_types.clj`: `schema-namespaces` scans
   `components/*/src` and `bases/*/src` for files that declare a function
   schema; `kondo-config` loads them, collects the metadata and builds the
   clj-kondo config with `malli.clj-kondo`; `emit!` writes it; `check!` exits
   1 when the committed file is missing or different.
2. `bb.edn`: `lint:malli-types` and `lint:malli-types:check` (JVM, `-X:dev`,
   about 26 s locally).
3. `ci.yml`: the Test job runs the check after the development tests and
   uploads its log. It runs there, not in Lint, because the `:dev` classpath
   includes the private `workbench-contract` git dependency, which only the
   Test job has access to.
4. `.clj-kondo/metosin/malli-types-clj/config.edn`: generated from the gate
   interface's schemas.
5. `gate/interface_schema_test.clj`: the one deliberate wrong-type call (it
   tests instrumentation's invalid-input error) carries a
   `:type-mismatch` ignore.

## Testing Plan

1. `malli-types-test`: a test function's `:malli/schema` becomes a
   `:type-mismatch` entry with the declared args and return; the scan finds
   `ai.miniforge.gate.interface` and does not scan test trees.
2. `bb lint:malli-types:check` passes on the committed file and exits 1 after
   the file is replaced with an empty config.
3. A probe calling `check-gate` with a string, `check-gates` with a keyword,
   and `(inc (passed? ...))` gets three clj-kondo errors; the correct call gets
   none. The 23 source and test files that use the gate interface lint with no
   new errors besides the deliberate call above.

## Deployment Plan

Development and CI only.

## Related Issues/PRs

1. #2297 (gate function schemas and test instrumentation).

## Checklist

- [x] Stale types fail the check task
- [x] Existing gate callers lint clean against the types
- [ ] CI green, Copilot comments resolved
