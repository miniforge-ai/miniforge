<!--
  Title: Bind deployment authority to canonical runtime inputs
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(deploy): bind exact runtime authority

## Overview

Corrects the deployment authority boundary before the live governed
deployment flow is wired. Grants prefer the canonical execution ID, retain a
temporary `:run-id` fallback for the existing unwired caller, and bind the
resolved Kubernetes context. Policy checks consume the Pulumi preview they
were designed to inspect.

## Changes

- Build the closed grant request from `:execution/id` (or the temporary legacy
  `:run-id` fallback) and one resolved target.
- Fail closed without issuing a grant when deployment policy blocks, including
  when the successful provision phase did not retain its Pulumi preview.
- Classify both deployment rules against the provision phase preview.
- Keep rendered-manifest validation separate from Pulumi-preview policy checks.
- Persist rendered manifests, server dry-run output, rollback information, and
  application label in the exact effect proposal.
- Pin the two deployment policy rule IDs in the DecisionEnvelope.
- Cover the production context shape, policy inputs, preflight evidence, and
  correlated effect, grant, and envelope identifiers.

## Verification

- `bb pre-commit`
- `clojure -M:poly test brick:phase-deployment`
- Adversarial review against Clojure, function, component, and stratified
  design standards.

## Deployment

No live deployment path changes in this PR. PR #1794 consumes this corrected
domain boundary when it wires governed deployment into `enter-deploy`.
