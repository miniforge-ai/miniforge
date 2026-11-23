<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: trusted runtime peer configuration

## Overview

Add trusted-host runtime peer types for the #2559 → #2555 dependency chain.
This PR is based on main and carries no process execution or lock proof.

## Motivation

The peer proof needs an installed launcher represented as an absolute File and
an absolute classpath entry list. Separating these types preserves the layering
and leaves review capacity for #2559's I/O/cleanup regression without weakening
tests or raising the PR limit.

## Changes in detail

Schema declarations stay in peer-spec; path predicates stay in peer-validation.
Every classpath entry, including the final entry, must be absolute and nonempty.
Configuration has exactly its named fields. No PATH lookup, shell parsing,
file-existence claim or execution authority is introduced.

## Testing plan

Pure producer-shaped factories cover valid absolute paths, spaces and multiple
entries, relative or missing values, trailing/middle empty entries, wrong types
and unknown keys. Verify Java 21 products, native Babashka and a fresh package
with strict normal gates and independent controls.

The packet passes 4 tests / 17 assertions in each Java 21 product, source
Babashka and a fresh package. Four independent controls produce 4 / 6 / 1 / 1
expected failures and zero errors on Java 21 and Babashka: executable type and
absoluteness, all classpath entries, closed fields, and preserved empty tails.

## Deployment plan

No live providers or infrastructure actions. These are data types for a trusted
host boundary; #2559 validates them before native use.

## Related issues/PRs

PR #2559 consumes this foundation, followed by #2555 qualification.

## Checklist

- [x] Implementation, proof and owner standards/adversarial pass.
- [ ] Current-head CI, automatic/full review, resolved threads and merge gates.
