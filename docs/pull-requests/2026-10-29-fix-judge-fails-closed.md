<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(semantic-analyzer): the policy judge fails closed on failed calls, bad answers and its deadline

## Overview

The semantic policy judge turned "no judgement" into "no violations". A failed
judge call or an answer that was not a finding list read as a clean file, so the
acting semantic rules behind `:policy-verify` and `:policy-review` passed during
a model outage. This PR makes each of those cases a violation that blocks, and
bounds the gate's batched judge with a deadline that also fails closed.

## Motivation

1. `parse-judge-response` returned `[]` when the answer was not an EDN vector.
2. The judge calls read only `:content`. A failed call (`llm/complete` returns
   `{:success false :error ...}`) therefore also parsed to `[]`.
3. `analyze-rules-on-files`, the gate's batched judge, had no overall bound: one
   call per changed file, for as long as the backend took.

## Changes in Detail

1. `parsing/parse-judge-response` returns nil for an answer that is not a
   finding list. A real `[]` still means no findings.
2. `judge/analyze-file` and `judge/analyze-file-batched` turn a failed call or an
   unparseable answer into a judge-failure violation for every rule asked about
   (`parsing/judge-failure-violation`). The violation carries
   `:semantic/judge-failure` (`:call-failed` or `:unparseable`), and its message
   carries the call's error or an excerpt of the answer. The gate already blocks
   on any violation of an acting rule.
3. `changed-files/analyze-rules-on-files` takes an optional `{:deadline-ms}`
   (default `limits/judge-deadline-ms`, 20 minutes). Each file still unjudged
   when it passes gets a `:deadline-exceeded` failure for each applicable rule,
   without a call, and the status is `:deadline-exceeded`. No file is skipped.
4. `limits.edn` holds the deadline and the answer-excerpt length
   (`:judge-answer-excerpt-chars`, 200).
5. New message catalog `config/semantic-analyzer/messages/en-US.edn` and its
   translator `semantic-analyzer.messages`; the component now depends on
   `messages`.

Left out: the single-rule path's 5-file cap (`max-files-per-rule`, not used by the
gate) is not copied to the batched path, because dropping files would leave them
unenforced.

## Behaviour change

A judge outage, a malformed answer, or a judgement that runs past the deadline
now blocks the acting semantic rules for the affected files instead of passing
them. The `miniforge scan` path (`analyze-rules-parallel`) reports the same
judge failures as violations. A change large enough to reach the 20-minute
deadline blocks until the deadline is raised in `limits.edn`.

## Testing Plan

1. `parsing_test.clj`: prose, nil, malformed and non-vector answers parse to nil.
2. `judge_test.clj`: an unparseable answer and a failed call each give one
   `:semantic/judge-failure` violation; a failed batched call blocks every rule
   in the batch.
3. `changed_files_test.clj`: with `:deadline-ms 0`, no call is made, the
   applicable file becomes a `:deadline-exceeded` violation, and the
   non-applicable file is still skipped.
4. Against #2093's semantic-analyzer source these three namespaces fail
   (9 failures, 2 errors) and pass with this change.
5. `components/semantic-analyzer`, `components/gate` and
   `components/policy-pack` (497 tests) pass on this branch merged with current
   `main`.

## Deployment Plan

No migration. Takes effect with the next CLI build.

## Related Issues/PRs

Builds on #2093 (the `core.clj` split into seven namespaces, merged as
adccc8537). This branch merged `main` after that squash; the merge took this
branch's side for the semantic-analyzer files, since `main` had not changed
them apart from #2093, and dropped #2093's pre-rename PR doc.

## Checklist

- [x] Regression tests fail without the fix
- [x] Adversarial review and pinned-standards review
- [x] #2093 merged and this PR retargeted to `main`
- [ ] CI green, Copilot comments resolved
