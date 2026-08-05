<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# docs: complete OPSV evidence references

## Overview

Completes the N6 OPSV evidence shape with the aggregate reference fields already
required by its immutable-finalization prose.

## Motivation

N6 requires finalization to preserve every accumulated event, artifact, and
capability reference, but version 0.7.0 provided no canonical keys for those
sets. Implementations would otherwise need undocumented extensions.

## Layer

Specification — normative N6 evidence contract only.

## Changes in Detail

- Add `:opsv/event-refs` for the complete N3 event identifier set.
- Add `:opsv/artifact-refs` for the complete artifact identifier set.
- Add `:opsv/capability-refs` for the complete N10 capability identifier set.
- Advance N6 to 0.7.1-draft and record the compatibility amendment.

## Testing Plan

- Run Markdown formatting and repository pre-commit checks.
- Verify the §2.8 prose and canonical map now describe the same preservation
  contract.

## Deployment Plan

No deployment or migration is required; this completes a draft contract before
its OPSV evidence implementation ships.

## Checklist

- [x] Event references have a canonical field
- [x] Artifact references have a canonical aggregate field
- [x] Capability references have a canonical field
- [x] Existing detailed artifact and governed-effect fields remain unchanged
