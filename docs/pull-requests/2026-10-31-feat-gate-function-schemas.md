<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat(gate,schema): function schemas on the gate interface, checked in tests

## Overview

The gate interface's `check-gate`, `check-gates`, `repair-gate`, `passed?` and
`failed?` now declare their argument and return shapes as `:malli/schema` var
metadata. A new `schema/instrument-fixture` turns those declarations on for the
duration of a test namespace, so a gate that returns a result in neither
accepted shape fails the test that ran it. Production does not check them.

## Motivation

`passed?` accepts two result shapes, `{:passed? bool ...}` and a
`response/success` / `response/error` map, and reads anything else as failed.
That is safe but silent: a gate that drifted out of both shapes failed every
implement phase in a dogfood run before anyone saw why (b5f5586f9, 83ec44b86).
About 68 of the repo's fix commits were wrong-key, nil or shape mismatches
between a producer and a consumer; this is a pilot of catching that class at
a component boundary, in tests, without changing runtime behaviour.

## Changes in Detail

1. `schema.instrument/instrument-fixture` (re-exported from the schema
   interface): collects `:malli/schema` metadata in the given namespaces,
   instruments them while the wrapped tests run, and restores the original
   functions afterwards. `m/=>` declarations work too.
2. `gate.schema`: `CheckResult` (the gate's result in either shape, tagged
   with `:gate`), `RepairResult`, and `ChecksResult` (closed: `:passed?`,
   `:results`, `:failed-gates`).
3. `gate.interface`: schema metadata on `check-gate`, `check-gates`,
   `repair-gate`, `passed?`, `failed?`. Inputs: gate keyword(s), any
   artifact, a map or nil context.
4. The six gate test namespaces that call the interface use the fixture;
   their 102 tests pass with every call checked.
5. `gate/deps.edn` declares the schema component, which `gate.interface`
   already required without declaring.

## Testing Plan

1. `schema.instrument-test`: invalid input and invalid output throw under the
   fixture (for both `m/=>` and metadata declarations); outside the fixture the
   original function runs unchecked.
2. `gate.interface-schema-test`: a test gate whose check returns `{:verdict :ok}`
   makes `check-gate` and `check-gates` throw `:malli.core/invalid-output`; its
   repair does the same for `repair-gate`; a string gate name throws
   `:malli.core/invalid-input`; `response/success` results pass. These tests
   fail without instrumentation, so they show the fixture is active.
3. The existing gate tests (interface, format, policy-pack, opsv, no-secrets,
   tests-pass) pass with the fixture on.

## Deployment Plan

No runtime change. Schemas are var metadata and are only checked inside
instrumented tests.

## Related Issues/PRs

1. Follow-ups in the same pilot: emit clj-kondo types from these schemas; the
   same treatment for the DAG task-state constructors.

## Checklist

- [x] Shapeless gate results fail instrumented tests
- [x] Existing gate tests pass with instrumentation on
- [ ] CI green, Copilot comments resolved
