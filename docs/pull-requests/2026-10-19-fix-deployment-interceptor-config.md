<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: retain native deploy interceptor configuration

## Purpose and layer

Deployment phase construction: preserve the configured phase identity, transition
targets, gates, budget and deployment options, then bind that effective config at
entry. The existing phase registry remains the owner of default and policy-gate
merging. A named entry adapter avoids embedding a closure in the interceptor map.

## Dependencies and scope

Based on main `26bbbd589`. This slice changes only the native deploy factory.
Provision and validate have the same defect but need separate namespace-strata
cleanup before their factories can be changed under the existing standards.
No provider operations, journal migration or terminal-publication policy changes.

## Verification plan

- Compare effective configuration with the canonical registry merge.
- Verify configured entry replaces stale phase config and preserves workflow input.
- Exercise actual runner selection and recovery after native deployment config
  refusal, without infrastructure effects.
- Run phase-deployment consumers, build the CLI, and verify the isolated package.
- Enforce normal signed hooks, budget, inferred strata, adversarial standards,
  exact-head review and all CI before merge.

## Regression evidence

The previous CLI fails 10 of 11 assertions across three tests. It drops phase
identity and supplied options, leaves stale entry config in place, and turns the
native failure/recovery workflow into a pipeline exception with no phase results.
This is a runner integration regression, not only a synthetic map-shape mismatch.

## Local verification and adversarial pass

The phase-deployment consumer suite passes (22 seconds); Polylith selects
Miniforge, the product containing this component. Rebuilt CLI: 39,187,689 bytes.
The isolated package passes 77 tests / 223 assertions across every deployment test
namespace plus the real runner regression, with no production source overlays.
All four changed namespaces match inferred strata; kondo has no warnings/errors.

Checked default registration before construction, explicit override precedence,
stale-context replacement, named entry binding, unchanged leave/error ports and
native failure/recovery routing. Removed the now-unused local default-config
value. Registry merging remains authoritative; no duplicate gate policy is added.

## Deployment

Normal CLI/package deployment. No stored-data migration is required.
