<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# docs: Clarify contributor and configuration prose

## Scope

Shorten sentences and remove filler flagged by the prose gate.
Preserve engineering requirements, budget limits, configuration behavior, and documented verification claims.
Add missing author metadata to touched PR documents.
Align the push-authentication test with main's existing explicit lease, retaining its credential-isolation checks.

## Verification

Run the prose checker and normal formatting and commit hooks.
Review each changed paragraph for preserved technical meaning.
No runtime code changes; new execution tests are unnecessary.
Run the existing OCI executor tests after correcting the stale push assertion.

## Deployment

Documentation only; no migration or configuration changes.
