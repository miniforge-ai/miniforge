<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Add monotonic OPSV mutation admission

## Overview

Provide a trusted runtime fence that prevents new operation admission after stop.
Already admitted operations may finish or return an unknown outcome.

## Motivation

Separate this foundation from #1935 to keep its grant-lifecycle review fixes and
regressions within the 600-line PR budget, without dropping tests or overriding limits.

## Layer and dependencies

Infrastructure runtime boundary within `opsv-actuation`, exposed through its
validated public interface. No application or provider dependency is introduced.

## Changes in detail

- Admission and stop share one atomic state transition.
- The fence has no reset operation and cannot be restored from input data.
- In-flight counts unwind in `finally`, including callback exceptions.
- Exceptions return localized anomaly data without leaking callback details.
- Stopping does not promise network cancellation or rollback.

## Testing plan

Four fence tests cover admission, monotonic stop, in-flight completion, exceptions,
and invalid handles. Run the full coordinator suite, standards scan and commit hooks.
The same implementation passed packaged Babashka execution in the dependent branch.

## Deployment plan

No external effects are enabled. Application hosts must retain the fence and wire
their global N8 stop signal to it. Grant revocation remains application-owned.

## Related issues/PRs

N7 governed actuation; prerequisite extracted from #1935 without rewriting its history.

## Checklist

- [ ] Tests, standards and hooks pass
- [ ] Final-head review settled, all checks green, conflict-free merge
