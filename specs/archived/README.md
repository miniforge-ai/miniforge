<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Archived Specifications

**Status:** Superseded
**Date Archived:** 2026-01-23

This directory contains specifications that were created during early product development
and have been superseded by the normative specifications in `/specs/normative/`.

## Purpose

These files are retained for historical reference to understand the evolution of miniforge's
design and architecture. They represent earlier iterations of product thinking that informed
the final normative specifications.

## Do NOT Use for Implementation

**⚠️ WARNING:** These specifications are **outdated** and **should not** be used for implementation.

For current, authoritative specifications, see:

- **Entry point:** [`/specs/SPEC_INDEX.md`](../SPEC_INDEX.md)
- **Normative specs:** [`/specs/normative/`](../normative/)

## Contents

This archive includes:

- **Product specs:** Early product vision and architecture documents
- **Component specs:** Initial component-level specifications (`.spec` format)
- **Implementation specs:** EDN-format specifications for specific features (`.spec.edn`)
- **Flow diagrams:** Early workflow and process documentation

## Relationship to Current Specs

The content from these archived specs has been:

1. **Synthesized** - Key concepts extracted and incorporated into normative specs
2. **Updated** - Requirements refined based on implementation experience
3. **Formalized** - Converted to RFC 2119 language (MUST/SHALL/SHOULD/MAY)
4. **Reorganized** - Structured into 6 normative specifications (N1-N6)

The content was synthesized into the current normative specs (N1-N6).

## Migration Guide

| Archived Spec | Current Location |
|---------------|------------------|
| `architecture.spec` | [`N1-architecture.md`](../normative/N1-architecture.md) |
| `miniforge.spec` | [`SPEC_INDEX.md`](../SPEC_INDEX.md) |
| `workflow-*.spec.edn` | [`N2-workflows.md`](../normative/N2-workflows.md) |
| `policy-pack.spec` | [`N4-policy-packs.md`](../normative/N4-policy-packs.md) |
| `cli-*.spec.edn` | [`N5-cli-tui-api.md`](../normative/N5-cli-tui-api.md) |
| `operational-modes.spec` | [`informative/operational-modes.md`](../informative/) (if exists) |
| `learning.spec` | [`informative/learning-meta-loop.md`](../informative/) (future) |

---

**For current specifications, always start at:** [`/specs/SPEC_INDEX.md`](../SPEC_INDEX.md)
