<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# fix(test): add explicit phase loader support

## Summary

This PR makes phase loader tests use explicit test-support resources instead of
assuming product phase namespaces are ambient on every narrowed project
classpath.

## Problem

The restored stable-derived Polylith test path exposed that `phase` tests were
passing only because wider repo classpaths happened to include product phase
resources. Under project-scoped execution that assumption breaks.

## Changes

- add `config/phase/test-support-namespaces.edn`
- add `phase/loader_support.clj` with dedicated test-support phase namespaces
- update `phase.loader-test` to bind the explicit test-support loader config
- update `phase.interface-test` to exercise the explicit phase test-support path

## Validation

- `ai.miniforge.phase.loader-test`
- `ai.miniforge.phase.interface-test`
- full `bb pre-commit` via the normal commit hook path
