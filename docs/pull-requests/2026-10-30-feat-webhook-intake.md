<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Verified GitHub deliveries into the event stream

## Overview

New `webhook-intake` component: verifies GitHub webhook signatures, maps deliveries to the events
workflow triggers match, and publishes them to an event stream, at most once per event.

## Motivation

`workflow/create-event-trigger` matches event-stream events and starts workflows, but no event ever
arrived from outside the process: there was no inbound route.

## Changes in Detail

1. `signature`: HMAC-SHA256 of the raw body under the webhook secret, compared with
   `MessageDigest/isEqual`; a blank secret never validates.
2. `github/delivery->events`: merged `pull_request` → `:pr/merged`; opened/reopened → `:pr/opened`;
   completed `check_suite` with failure or timed_out → `:pr/ci-failed`, one per same-repository PR
   the suite lists, carrying `:ci/app` and `:ci/check-suite-id`. GitHub sends one suite per app and
   per Actions workflow run, so a passing suite is not mapped (it says nothing about the PR's other
   checks). Fork PRs, and suites listing no PR (fork suites carry the fork's own branch name and list
   none), map to nothing; so does everything else, `ping` included.
3. `handler`: POST `/webhooks/github` only; 401 bad or missing signature, 405 non-POST, 404 other
   paths, 400 non-JSON or missing `X-GitHub-Delivery`, 202 accepted.
4. `deliveries` + `publish`: deliveries are keyed by their body signature (`X-GitHub-Delivery` is not
   signed, so a captured delivery replayed under a fresh id is still a redelivery). Each has a status
   and a count of published events (newest 1024 kept; in-flight deliveries are never evicted). A
   replay after a restart or after 1024 newer deliveries is not detected. Redelivery of a finished
   delivery → 200; while
   still publishing → 409. A refused publish (a returned rejection or anomaly, before any sink or
   subscriber runs) → 500 and a redelivery resumes at the first unpublished event. A publish that
   throws has an unknown outcome (a subscriber filter can throw after delivery) → 500, then 409
   `publish-outcome-unknown`, never a retry that could start a workflow twice.

The webhook must use content type `application/json`.

## Testing Plan

Signature: GitHub's documented example (checked with `openssl dgst -sha256 -hmac`) and tampering.
Mapping: payloads built from real GitHub API objects for this repo (merged #2254, open #2259, the
failing check suite of #2244's CI run). Handler: a real event stream with a collecting subscriber;
refusals publish nothing; redelivery, in-flight redelivery, refused, thrown and partial publishes.
Deliveries: claim lifecycle, eviction, all-in-flight at capacity.

## Deployment Plan

No migration. The listener and CLI command that use this component come in #2274.

## Related Issues/PRs

- #2274: listener (loopback by default, secret required) and `mf webhook serve`.

## Checklist

- [x] Stratum lint, clj-kondo, commit and PR budgets pass
- [x] Copilot review comments answered
