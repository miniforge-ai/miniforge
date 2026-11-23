<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: share exact Kubernetes controller identity checks

## Overview

Use one controller identity rule for observed Kubernetes resources.
Absent identity and matching names alone cannot establish ownership.

## Motivation

N11 full stop requires proof of immutable run-owned resources. Existing
OPSV membership already matches controller UIDs. The rule should be shared
with future containment without treating containment as ordinary release.

## Changes in detail

Domain layer; depends on the existing Kubernetes target component.
Move general controller identity checks into its class 1 internal API.
Reject missing or ambiguous references and require matching named namespaces
when determining ownership. Keep historical ReplicaSet recognition in OPSV.
No runtime mutation, new authorization, or provider request is added.

## Testing plan

Exercise a Job/Pod controller edge, same-name replacements, missing UIDs,
missing namespaces and multiple controllers. Run existing OPSV inventory,
member and replica tests against the shared implementation.

Local results: 48 product tests and 827 assertions passed; the isolated
Babashka component pass covered 5 tests and 61 assertions. A missing-identity
fault failed assertions without runtime errors. Strict lint, inferred
namespace depths, and Polylith passed.

## Deployment plan

No deployment action. This proves resource relationships only. Full-stop
dispatch still requires retained run ownership, current authority, provider
preconditions, and actual termination confirmation.

## Related issues/PRs

PR #2528 defines the N11 full-stop containment contract.

[Pinned standards guide](https://github.com/miniforge-ai/miniforge-standards/blob/72f451d3d7e0ad82079610bec61be0399a2b7117/agents.md)
and [catalog](https://github.com/miniforge-ai/miniforge-standards/blob/72f451d3d7e0ad82079610bec61be0399a2b7117/index.mdc).
