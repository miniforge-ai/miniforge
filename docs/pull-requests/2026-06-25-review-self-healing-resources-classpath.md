<!--\n  Title: Miniforge.ai\n  Author: Christopher Lester (christopher@miniforge.ai)\n  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.\n-->
# Wire self-healing resources into review classpaths

## Overview

`bb review` failed before scanning standards violations because loading the
self-healing component could not resolve
`config/self-healing/backend-health.edn`. The resource exists in the component,
but the workspace and Babashka review classpaths included only
`components/self-healing/src`.

This PR adds `components/self-healing/resources` beside the existing
self-healing source path in both classpath declarations used by local review.

## Verification

- `bb review` now completes and reports the current standards backlog.
- The resulting backlog is 308 needs-review violations, all under
  `005 — Exceptions as Data`.

## Scope

No runtime behavior changes. This only makes the existing self-healing resource
visible to repo-level review and development classpaths.
