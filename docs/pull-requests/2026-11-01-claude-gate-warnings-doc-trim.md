<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# docs: Trim gate-warnings prose to the documentation-discipline limits

## Overview

Two pieces of prose that #2303 added to `workflow/execution_transition.clj`
are brought within documentation-discipline (dewey 600). Behavior does not
change.

## Motivation

The rules review of #2303 ran after it had merged and failed two items
under dewey 600:

- The `gate-warnings` docstring narrated how the code came to be. Dewey 600
  rejects history in source docs. That story belongs in the PR doc, and
  `docs/pull-requests/2026-11-01-fix-gate-warnings-survive-retries.md`
  already tells it.
- The comment on the `:phase/gate-warnings` clause ran three lines, and
  dewey 600 caps inline comments at one.

## Changes in Detail

- The `gate-warnings` docstring states the return shape `[{:gate kw :warnings [..]}]`.
  Entries follow gate order, one per warning gate; no warnings returns nil. Each claim was checked
  against the function body.
- The clause comment is now one line and keeps the reason the clause
  exists: the envelope cannot carry warnings.

## Testing Plan

Documentation only, so per dewey 716 the PR needs no test changes. The
edited file parses cleanly on the Clojure 1.12 reader. CI runs the suite.

## Deployment Plan

None. There is no runtime change.

## Related Issues/PRs

These docs describe the change in #2303.

## Checklist

- [x] Gate 1 (docs-only form): every factual claim checked against the repo
- [x] Rules review: 009, 600, 716, 721
- [ ] CI green on the current head
- [ ] Copilot findings resolved on the current head
