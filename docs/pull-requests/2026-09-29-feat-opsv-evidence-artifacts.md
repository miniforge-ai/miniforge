<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Publish real OPSV phase artifacts

## Overview

Persist experiment packs, measured ramp steps, convergence results, policies,
verification results and actuation records as immutable artifacts. Accumulate
references only after publication is confirmed.

## Motivation

N7 evidence must point to real durable material, not caller-provided placeholder
identifiers. Correlate each artifact with the workflow and preallocated bundle.

## Layer and dependencies

The phase application uses the public artifact and evidence interfaces. Pure
material selection and identity are separate from publication orchestration and
exception conversion. This branch depends on the durable artifact foundation
and the governed runtime/audit stack; merge those prerequisites first.

## Changes in detail

- Publish seven phase records through the immutable artifact API.
- Use the shared artifact constructor and content-bound identifiers.
- Record only confirmed artifact references in the evidence assembly.
- Link metric and policy events to their actual published artifacts.
- Preserve completed output when storage fails; classify JVM errors as fatal.
- Keep legacy callers unchanged unless trusted runtime options enable storage.

## Validation

The artifact lifecycle suite passes five tests and 24 assertions. Both consuming
projects pass 54 phase tests and 364 assertions each. The scoped standards scan
reports no findings across 52 files. Polylith, kondo, strata and commit hooks pass.

## Standards adversarial pass

Use the canonical artifact constructor instead of duplicating its record shape.
Separate pure projection, durable publication and exception conversion. Preserve
fatal classification at the application boundary and retain actual phase output.
Cross-component calls use public interfaces; diagnostics use the message catalog.

## Deployment and limitations

The trusted host must supply an existing canonical artifact directory. This
slice publishes phase material; final N6 construction and evidence-only recovery
are separate follow-ons. It does not enable live load or provider effects.

## Checklist

- [x] Focused regressions and consuming-project tests
- [x] Adversarial standards pass
- [ ] Final-head review settled and all CI, including Build, green
- [ ] Prerequisites merged; retarget main before merging
