## Summary

This PR packages the remaining Claude dogfood review-boundary fix from the `behavioral-verification-monitor` run on
`main`.

The remaining failure shape was narrower:

1. the reviewer could return a parsed review artifact with all gates passing
2. the only blocking issue could be an adaptive-timeout/stagnation message
3. Miniforge could still treat that as a real rejected review and redirect implement

## What Changed

### Reviewer timeout-only failures now fail as backend errors

- broaden the reviewer timeout-only classifier so it does not depend on the backend wrapper’s `success?` bit
- when the parsed review artifact says:
  - all gates passed
  - no real findings or recommendations exist
  - the only blocking issues are timeout/stagnation messages
  - the decision is negative
  then review now returns `:reviewer/backend-timeout` instead of a fake rejected review
- preserve the timeout message itself as the phase error message
- add regressions for both shapes:
  - backend wrapper reports failure
  - backend wrapper reports nominal success but the parsed review is still only a timeout echo

## Validation

- `clj-kondo` on touched files: clean
- focused JVM regression run:
  - `ai.miniforge.agent.reviewer-test`

## Live Behavior

- persisted dogfood review snapshot showed `4/4` gates passed, no real findings, and only an adaptive-timeout blocking
  issue
- that exact shape is now classified as a reviewer/backend failure instead of a code-review rejection

## Follow-up

- rerun the Claude dogfood workflow from the updated branch state so the new reviewer timeout fix is actually in play
