<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: preserve workflow heartbeat ownership across failures

## Scope and dependencies

Application-layer ownership, based on refactor/shared-resource-cleanup (#2079).
This documented dependent branch uses its public cleanup interface rather than
copying failure policy into workflow. Open only after the foundation merges and
the branch is aligned with main.

Propagate heartbeat-start anomalies instead of storing them as resource handles.
Stop every owned heartbeat even when another stop fails. Keep callbacks focused
on lifecycle receipts and caller notification, with resource ownership separate.
This does not yet claim substantive progress-source instrumentation or N7 completion.

## Verification and deployment

Exercise actual callback admission and cleanup with deterministic failing ports;
verify remaining handles are released and failure identity survives. Run workflow
consumers serially, rebuild the CLI, test the isolated package, and apply the
adversarial standards pass. Keep normal budgets, signed hooks, fresh exact-head
review and all CI gates. Deploy through the ordinary package build.

All three workflow-consuming projects passed serially (1m28s); Data Foundry
does not consume workflow. The fresh CLI build is 39,216,888 bytes. Isolated
package regressions passed 11 tests/43 assertions with no production source
overlays. The parent-relative standards scan is clean, inferred strata are
within three, and the implementation plus tests fits one normal commit.

The owner is specific to the runner's sequential lifecycle callbacks. It holds
no lock across publication or external cleanup. Re-entry stops the previous
handle before acquisition, and refused cleanup retains that handle for retry.
Callbacks propagate refusals before notifying callers or publishing completion.
Actual activation-captured progress ports and runner-wide environment cleanup
remain subsequent integration steps rather than implicit claims of this PR.

After #2079 merged, alignment with main retained the reviewed heartbeat runtime
and resolved only the parent's documentation conflict. All three workflow
consumers passed again (1m29s), the rebuilt CLI is 39,224,633 bytes, isolated
regressions passed 11 tests/43 assertions, and all task entry classpaths loaded.
The actual-main standards scan is clean and the whole PR is 166 reportable lines.

Review strengthened the fatal-stop regression: the ordered fixture now executes
the failing handle first and asserts the exact call sequence, so cleanup that
aborts on that exception cannot pass by stopping the other handle beforehand.
