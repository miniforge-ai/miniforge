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

## Verification

Run the prose checker and normal formatting and commit hooks.
Review each changed paragraph for preserved technical meaning.
No runtime code changes; new execution tests are unnecessary.

## Deployment

Documentation only; no migration or configuration changes.
