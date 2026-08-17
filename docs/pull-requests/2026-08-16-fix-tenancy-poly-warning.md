<!--
  Title: Declare packaged tenancy component necessary
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(poly): declare packaged tenancy component necessary

## Layer

Workspace configuration.

## Depends on

None.

## Overview

The Ariadne 3a tenancy component is deliberately packaged in the Miniforge
runtime projects before its application wiring lands. Declare that intent in
the Polylith workspace instead of reporting the component as unnecessary.

## Changes

- Mark `tenancy` necessary in `miniforge-core`, `miniforge`, and
  `miniforge-tui`.
- Retain the component in each packaged artifact while clearing Warning 207.

## Verification

- `clojure -M:poly check`
- `bb pre-commit`
