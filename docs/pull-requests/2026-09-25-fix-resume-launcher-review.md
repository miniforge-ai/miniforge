<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: review fixes to the resume launcher

## Overview

Inserted above `feat/resume-launcher` (#1915), below
`feat/resume-launcher-hardening`. Review fixes to the launcher from #1915.
They would put #1915 past the 600-line PR budget, so they are a PR of
their own.

## Motivation

Copilot's review of #1915:

- `--from-phase` was said to be prepended to the child's argv. It is not:
  the options follow `resume <id>`. A test now runs the argv through the
  CLI's own dispatch.
- The launcher detaches through `/bin/sh`, `nohup` and `setsid`. Native
  Windows, a supported CLI platform, has none of them.
- A poll that throws ended the start wait with an exception. The
  verification pool records that as `:application-error`, and the child is
  never killed.

## Changes in Detail

- The child's argv is checked through `mf`'s dispatch: it runs `resume`
  with the workflow id, `--run-id`, `--correlation-id` and `--from-phase`.
- Native Windows: `launcher` returns nil there, so no launcher is
  registered and a retry fails `:no-resume-launcher`. The message for that
  code now names both causes: native Windows, and an unknown command
  (`MINIFORGE_CMD`). `launcher` takes its facts (`os-name`, the command
  line) as an argument too, for tests.
- A start-wait poll that throws counts as a poll without evidence, and the
  child as alive. Only the deadline ends a wait that keeps failing: the
  child is then killed and reported not started.

## Testing Plan

- The argv through `-main` reaches `resume` with its options, with
  `--from-phase` coerced to a keyword. (Prepended, it would arrive as a
  string.)
- No launcher on Windows; one on Linux and macOS.
- A throwing poll followed by the start evidence verifies the launch; no
  run directory yet is no evidence.
- Pre-commit hook per commit.

## Deployment Plan

No migration. Nothing is registered until `feat/shared-process-handles`.

## Known Limits

No retries on native Windows. WSL is a Linux host.

## Related Issues/PRs

Stack: `feat/operator-verification-pool` (#1920), `feat/resume-launcher`
(#1915), this PR, `feat/resume-launcher-hardening` (#1916),
`feat/shared-process-handles` (#1917), `feat/operator-serve` (#1918).

## Checklist

- [x] Tests for new behaviour
- [ ] Review comments addressed; CI green
