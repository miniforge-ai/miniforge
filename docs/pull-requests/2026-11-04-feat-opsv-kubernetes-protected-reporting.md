<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Protect native Kubernetes reporting material

## Overview

Prepare data-minimized native reporting copies through the existing evidence scan
and redaction policy. Original observations remain comparison inputs, not reports.

## Motivation

Native environment entries use generic name/value fields. Commands, annotations,
configuration payloads and unknown native fields can also contain credentials.
Generic secret-key matching alone does not identify every native payload role.

## Changes in Detail

Base branch: main, including verification capture #2366.

Add one reporting-only API with a fixed, versioned field policy. Retain native
identity, provenance and scaling fields. Classify payloads, literal environment,
commands, labels, annotations and unknown native fields as confidential.
Treat native Pods, controller templates and versioned PodSpec projections alike.
Bind native field treatment to both API version and kind. Classify list payloads
even when ListMeta or item TypeMeta is omitted. Foreign API kinds receive the
conservative payload treatment, not a native Pod's public-field policy.

Classification preserves original values for the shared scanner. A producer-owned
secret key invokes existing redaction without duplicating secret or PII patterns.
The shared evidence preparer scans before redaction, preserving original findings.
Confirm that every surviving producer classification contains the canonical marker.
If protection fails, return a value-free anomaly rather than unsafe material.

Require bounded inspectable data, simple keys and no Clojure language metadata.
Anomaly-shaped payloads remain artifact data rather than pipeline control.

Compute comparison hashes from original observations before requesting a report.
Reporting copies are deliberately lossy: never use them as manifests, comparison
inputs or recovery contracts. This API introduces no storage, effects or authority.
Native preparation and runner integration remain separate.

## Testing Plan

Cover Secret and ConfigMap payloads, literal environment, commands, templates,
semantic PodSpecs, unknown kinds and retained identity/scaling information.
Prove original comparison differences survive while reports withhold values.
Retain original PII findings and refuse opaque, deferred or metadata-bearing data.
Simulate failed shared redaction and require a value-free failure.

Both product contexts, poly check and a fresh CLI build pass after the native-type
correction. Isolated packaging passes 158 tests and 908 assertions.
All nine deliberate privacy/admission defects are detected without test errors.
The 13 focused packaged cases contain 60 assertions and measure 1.2–22.1 ms each.
The two native-type regressions reproduce four assertion failures and zero errors
against the prior package. The committed scan reports zero findings across 275
files; explicit strata and full source/test lint pass without warnings or errors.
No gate or size exception is requested.

## Deployment Plan

Explicit reporting API only. No live experiments or cluster changes.

## Related Issues/PRs

N7 section 6.3; N3 protection and N6 evidence policy; verification capture #2366.

## Checklist

- [x] Reuse shared scanning and redaction without losing original findings.
- [ ] Complete owner, product, package, CI and full-review gates.
