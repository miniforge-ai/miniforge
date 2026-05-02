<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# spec/external-dependency-health-and-failure-attribution

## Summary

Adds a work spec for one of the main operational gaps surfaced by recent
dogfooding: Miniforge does not yet reliably distinguish product failures from
external dependency failures, user-environment/setup failures, and third-party
platform outages.

The new spec defines the implementation contract for:

- canonical dependency attribution data
- classifier integration over existing external/setup error patterns
- rolling provider/platform health state
- dependency health events and degradation integration
- CLI/dashboard blame assignment
- evidence and behavioral-observation integration
- test coverage across the boundary and projection layers

## Why This Matters

Dogfood runs exposed several materially different failures that currently risk
being surfaced as generic workflow failures:

- Claude backend unavailable or non-responsive
- Codex session-path permission failures in the executor environment
- Codex account/model incompatibility for a selected model
- future GitHub / Kubernetes / Jira / SaaS platform outages

Those are not all “Miniforge failed.” The system needs a canonical way to say:

- this is a Miniforge bug
- this is the user environment or account setup
- this is an external provider outage or limitation
- this is an external platform outage or auth problem

## What This Spec Defines

The new spec at
[external-dependency-health-and-failure-attribution.spec.edn](../../work/external-dependency-health-and-failure-attribution.spec.edn)
introduces a PR DAG covering:

1. canonical dependency attribution taxonomy in `failure-classifier`
2. classifier integration using the existing `external.edn` and
   `backend-setup.edn` pattern knowledge
3. provider/platform health projection in `reliability` and
   `supervisory-state`
4. dependency health event emission and degradation-policy integration
5. localized CLI/dashboard attribution for provider/platform failures
6. evidence and observe-path integration for dogfood and behavioral runs
7. boundary/projection tests across the affected components

## Scope

This PR is docs/spec only. It does not change runtime behavior yet.

The implementation work will follow as a short PR stack so we can close this
gap quickly and return to dogfooding with better attribution and operator
signal.

## Files

-
  [external-dependency-health-and-failure-attribution.spec.edn](../../work/external-dependency-health-and-failure-attrib
  ution.spec.edn)
