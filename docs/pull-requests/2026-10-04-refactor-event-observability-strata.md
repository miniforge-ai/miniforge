<!-- Title: Miniforge.ai -->
<!-- Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai) -->
<!-- Licensed under the Apache License, Version 2.0. -->
# refactor: Bound event observability namespaces

## Overview

Resolve the timeline's missing string namespace import and excessive abstraction
depth before enabling the acknowledged publisher. Separate field formatting from
event rendering, with no namespace exceeding three strata.

## Design and scope

- Timeline values own timestamp normalization, field extraction and truncation.
- Timeline rendering owns event dispatch and a named adjacency-preserving fold.
- Reuse one timestamp normalization path for rendering and gap detection.
- Preserve the public renderer, defaults, tool correlation and output shape.
- Replace a synthetic supervisory event map with its production constructor.
  Share the lifecycle test envelopes through one fixture factory. Stratify the
  test namespace without changing its assertions or scenario payloads.

The scope fixture remains valid before and after strict publication is enabled.
No publisher activation, durable-store ownership or evidence validation change is
part of this prerequisite. It depends on the publication engine branch.

## Validation and deployment

Require focused timeline and supervisory tests in JVM and packaged Babashka.
Adversarial review must check timestamp representations, missing-time adjacency,
truncation, tool correlation and the reordered test declarations.
Run normal hooks, consumer checks, standards scan, and current-head PR review.
Merge only after the prerequisite and all checks settle; preserve the worktree.
