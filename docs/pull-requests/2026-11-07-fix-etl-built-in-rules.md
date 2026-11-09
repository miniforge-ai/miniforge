<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: ETL runs the built-in transforms and rules, and preflights validate-pack

## Overview

The ETL runner now supplies pipeline-runner's built-in transforms and the
built-in quality-rule registry, and `etl validate` preflights the resolved
pipeline. A rule whose constructor fails is kept as data and refused by name.
This is part of #2422.

## Motivation

`run-pack` passed no `:transforms` and resolved quality rules against an empty
registry. In the shipped `risk-data` pack, `Derive Buffett Indicator` passed
records through unchanged, and both `Validate Quality` rules resolved to nil,
which the evaluator then called as functions. With the preflight PR below
this one, that pack is refused before it runs; this PR makes it run correctly.

Supplying the registry means rule constructors run during resolution. A
`:pattern` rule whose regex does not compile threw from `re-pattern`, out of
both `run-pack` and `validate-pack` (Copilot, on the preflight PR).

## Changes in Detail

1. `etl.runner`: `run-pack` registers the built-in transforms (caller
   transforms win) and resolves rules against the built-in rule registry.
   `validate-pack` preflights the resolved pipeline against its connectors.
2. `pipeline-config` rule registry: the `:pattern` constructor returns the
   descriptor with a `:rule/error` and no check fn when the pattern does not
   compile, instead of throwing.
3. Preflight: a rule carrying `:rule/error` is reported as
   `:quality-rule/invalid`, apart from `:quality-rule/unresolved`.
4. `risk-data` pack: `Normalize` declares `:stage/pass-through? true`; it only
   merges upstream datasets.

## Behavior changes

1. In the `risk-data` pack, `Derive Buffett Indicator` computes its ratio and
   `Validate Quality` evaluates its two rules.
2. An invalid `:pattern` regex is a structured preflight failure from both
   `run-pack` and `validate-pack`, not an exception.

## Testing Plan

1. ETL `runner_test`: all three shipped packs pass `validate-pack` with preflight.
   A pack naming an unknown transform fails with `:transform/unresolved`.
   A pack whose `:pattern` is `"["` fails with `:quality-rule/invalid`, with no exception.
2. Locally, pipeline-runner, pipeline-config and ETL namespaces pass.

## Deployment Plan

None.

## Depends on

Stacked on #2446 (preflight), which is stacked on #2444.

## Related Issues/PRs

Part of #2422, under tracker #2421.

## Checklist

- [x] Built-in transforms and rules wired into `run-pack`.
- [x] `validate-pack` preflights.
- [x] Rule-constructor failures become preflight problems.
