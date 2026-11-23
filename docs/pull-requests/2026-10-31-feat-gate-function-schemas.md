<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat(gate,schema): function schemas on the gate interface, checked in tests

## Overview

The gate interface's `check-gate`, `check-gates`, `repair-gate`, `passed?` and
`failed?` now declare their argument and return shapes as `:malli/schema` var
metadata. A new `schema/instrument-fixture` enables those declarations for a
test namespace. A result in neither accepted shape fails the test that ran it.
Production does not check them.

## Motivation

`passed?` accepts two result shapes, `{:passed? bool ...}` and a
`response/success` / `response/error` map, and reads anything else as failed.
That is safe but silent. A gate outside both shapes failed every implement
phase in a dogfood run before anyone saw why (b5f5586f9, 83ec44b86).
About 68 of the repo's fix commits were wrong-key, nil or shape mismatches
between a producer and a consumer. This pilot catches that class at a
component boundary in tests, without changing runtime behaviour.

## Changes in Detail

1. `schema.instrument/instrument-fixture` (re-exported from the schema
   interface) collects `:malli/schema` metadata in the given namespaces.
   It instruments them while the wrapped tests run, then restores the original
   functions. `m/=>` declarations work too. It throws
   IllegalArgumentException when the namespaces declare no function schema, so
   a fixture naming the wrong namespace cannot leave tests running unchecked.
   The schema interface loads `malli.instrument`, and thus `malli.generator`
   and test.check, wherever it is required. This takes about 16 ms under
   Babashka. Moving the fixture to a test-only component would avoid that.
2. `gate.schema` defines `CheckResult`: the gate convention or a response map
   tagged with `:gate`. Response maps include `response/success`,
   `response/error`, and `response/failure`'s `{:success false}`.
   `RepairResult` uses the same alternatives, with `{:success? bool}` as
   the convention. `ChecksResult` is closed: `:passed?`, `:results`,
   `:failed-gates`. The predicates read verdict
   keys before response keys. A present verdict must therefore be boolean
   even when `:status` is valid.
3. `gate.interface`: schema metadata on `check-gate`, `check-gates`,
   `repair-gate`, `passed?`, `failed?`. Inputs: gate keyword(s), any
   artifact, a map or nil context.
4. The six gate test namespaces that call the interface use the fixture;
   their tests pass with every call checked.
5. `gate/deps.edn` declares the schema component, which `gate.interface`
   already required without declaring.

## Testing Plan

1. `schema.instrument-test`: invalid inputs and outputs throw under the
   fixture for both declaration forms. Outside the fixture, the original
   function runs unchecked. A fixture over no namespace, or a namespace
   without function schemas, throws IllegalArgumentException.
2. `gate.interface-schema-test`: a test gate whose check returns `{:verdict :ok}`
   makes `check-gate` and `check-gates` throw `:malli.core/invalid-output`; its
   repair also makes `repair-gate` throw. A string gate name throws
   `:malli.core/invalid-input`. Both response result forms pass, including
   the behavioral gate's real repair, which returns `response/failure`.
   The rejection tests fail without instrumentation, so
   they show the fixture is active; the failure-shape test fails against a
   schema without `{:success false}`. A string `:passed?` or `:success?` beside
   a valid `:status` is rejected.
3. The existing gate tests (interface, format, policy-pack, opsv, no-secrets,
   tests-pass) pass with the fixture on.

## Deployment Plan

No runtime change. Schemas are var metadata and are only checked inside
instrumented tests.

## Related Issues/PRs

Follow-ups in the same pilot: emit clj-kondo types from these schemas and
apply the same treatment to DAG task-state constructors.

## Checklist

- [x] Shapeless gate results fail instrumented tests
- [x] Existing gate tests pass with instrumentation on
- [ ] CI green, Copilot comments resolved
