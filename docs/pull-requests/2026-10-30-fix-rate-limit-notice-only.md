<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(llm): read only a CLI notice as a rate limit, not an answer that mentions one

## Overview

`cli-output/rate-limited?` turned any successful CLI output that mentioned a rate
limit or a standalone `429` into a rate-limit error. It now reads only output of at
most 300 characters, and only notice phrasing.

## Motivation

The check exists for the Claude CLI's exit-0 notice ("You've hit your limit - resets
7pm (America/Los_Angeles)"), which arrives as the whole output. It ran on every
answer, streaming and non-streaming, for every CLI backend, and matched the bare words
"rate limit" and "429". So a correct answer such as "This PR adds a rate limit and
returns HTTP 429" came back as `{:success false}`, and so would an implementer's final
message about rate-limit handling. Copilot found it on #2285, where the web
dashboard's PR summary now goes through the llm client.

## Changes in Detail

1. `rate-limited?`: output longer than 300 characters (after trimming) is never a
   notice. The pattern keeps "you've hit your limit", "too many requests",
   "resets 7pm", and adds "usage limit reached"; "rate limit" must read "rate
   limited", "rate limit exceeded" or "rate limit reached"; a bare `429` no longer
   matches.

## Testing Plan

1. `rate-limited?-test`: the existing notices (Claude's limit message, "rate limit
   exceeded", "backend rate limited", "HTTP 429 too many requests") still match; new
   cases for answers that mention a rate limit or 429, short and long, do not (they
   fail on main).
2. llm component tests pass.

## Deployment Plan

Ships with the next release.

## Known limits

1. A short answer that uses notice phrasing ("requests get a too many requests
   error") is still read as a notice. Telling the two apart for certain needs the
   CLI's structured output (`--output-format json` and its `is_error` field).

## Related Issues/PRs

1. #2285 (dashboard PR chat and summary through the llm client).

## Checklist

- [x] Tests fail on main and pass here
- [ ] CI green, Copilot comments resolved
