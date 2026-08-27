# chore: lint staged prose with plainspeak in the pre-commit hook

## Overview

Adds a prose-lint step to `.githooks/pre-commit`: staged `.md`,
`.markdown`, and `.txt` files must pass plainspeak
(https://github.com/miniforge-ai/plainspeak).

## Motivation

Keep new prose free of marketing language, filler, meta-narration,
and LLM style artifacts, repo by repo, at the cheapest point.

## Changes in Detail

- The hook lints only staged prose files, so legacy documents block
  nothing until they are edited.
- The step is skipped when the `plainspeak` binary is absent, so CI
  and fresh machines are unaffected.
- Findings can be suppressed per line (`plainspeak:ignore`) or per
  finding code, via the repo-level `disable` list in
  `[tool.plainspeak]`.

## Testing Plan

Hook exercised locally with a staged prose file containing a known
finding (blocked) and after fixing it (passed).

## Deployment Plan

None; per-clone hooks activate via the repo's existing
`git config core.hooksPath .githooks` setup.

## Related Issues/PRs

Part of the workspace-wide plainspeak rollout.

## Checklist

- [x] Hook change reviewed
- [x] PR doc included
