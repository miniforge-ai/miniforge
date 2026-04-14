<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor: Pack-driven compliance scanner

**Branch**: `refactor/pack-driven-compliance-scanner`
**Date**: 2026-04-06

---

```yaml
generated-by: miniforge-compliance-scanner-refactor
type: refactor
components-touched:
  - policy-pack
  - compliance-scanner
  - workflow-compliance-scanner
```

---

## Overview

Replace the hard-coded `scanner_registry.clj` with a pack-driven architecture
where the compliance scanner reads detection patterns, file applicability,
remediation strategies, and classification hints from compiled policy packs.

## Motivation

Adding a new compliance rule previously required writing Clojure code in
miniforge. Policy rules belong in the policy pack — miniforge is the engine,
not the rulebook.

## Changes

### MDC Frontmatter Extension (`.standards`)

Added dot-notation `detection.*` and `remediation.*` fields to 3 MDC files:
clojure (210), datever (730), and header-copyright (810).

### Schema Extension (`policy-pack/schema.clj`)

Added `DetectionMode`, `RemediationStrategy`, `RemediationType`,
`ExcludeContext`, `RuleRemediation` schemas. Extended `RuleDetection`
and `Rule`.

### Compiler Extension (`policy-pack/mdc_compiler.clj`)

Added `group-dotted-keys`, `build-detection-config`,
`build-remediation-config`, `build-exclude-context`.

### Pack-Driven Scanner (`compliance-scanner/scan.clj`)

Added `globs->file-pred`, `build-suggest-fn`, `pack-rule->detection-config`,
`enrich-violation`. Rewrote `scan-repo` to use pack path.

### Generic Classifier (`compliance-scanner/classify.clj`)

Single `classify-one` using pack-enriched `:auto-fixable-default` and
`:exclude-contexts`.

### Pack-Driven Executor (`compliance-scanner/execute.clj`)

Dispatches on `:remediation-type` instead of `:rule/id`.

### Deleted

- `scanner_registry.clj` (167 lines)
- `scanner_registry_test.clj` (143 lines)

## Verification

42 tests, 119 assertions, 0 failures. Identical scan results to old
registry-driven scanner (222 violations, 216 auto-fixable).
