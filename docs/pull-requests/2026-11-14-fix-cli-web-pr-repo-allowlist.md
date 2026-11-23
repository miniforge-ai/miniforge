<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(cli): act only on configured repos in the PR web handlers

## Overview

The fleet dashboard's PR routes took the repository from the URL and
passed it to `gh`. They now act only on a repository listed in
`[:fleet :repos]` and answer 400 for any other.

## Motivation

The routes are `GET /api/pr/<repo>/<n>` and the `approve`, `reject`,
`chat` and `summary` POSTs under it. The URL is untrusted input. A
request naming any repository the local `gh` session can reach was
approved, rejected, read or summarised. That includes a cross-site POST
to the dashboard on localhost.

## Changes in detail

- `handlers/parse-configured-pr-path` wraps `parse-pr-path`. It returns
  nil unless the parsed repository is one of the configured repos.
- `pr-detail`, `approve`, `reject`, `chat` and `summary` take `repos`
  and use the new parser. `web/handler` passes the repos it already
  reads for the other routes.
- With no repos configured, every PR route answers 400. The dashboard
  lists no PRs in that state, so no link leads to those routes.
- Dashboard links are built from the configured repo strings, so a
  listed PR always matches the allowlist.

## Testing plan

`handlers_test.clj`:

- The parser accepts a configured repo, rejects an unconfigured one,
  and rejects everything when no repos are configured.
- Each of the five handlers answers 400 for an unconfigured repo and
  never calls the `github` namespace.

With the allowlist check removed, the parser test fails twice and the
handler test errors five times. The namespace runs 6 tests and 14
assertions with no failures.

## Deployment plan

No configuration change. Operators who use the dashboard already list
their repos in `[:fleet :repos]`.

## Known follow-up

A cross-site POST can still act on a configured repo. The dashboard has
no request-origin check. That needs its own change.

## Related issues/PRs

Based on `main`. No dependencies.

## Checklist

- [x] Regression tests fail before the fix and pass after it
- [x] Merged with current `main`
- [ ] CI green on the final head
- [ ] Review threads resolved
