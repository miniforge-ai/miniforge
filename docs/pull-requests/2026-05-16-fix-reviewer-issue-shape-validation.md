# Fix Reviewer Issue Shape Validation

## Summary

Dogfood exposed a reviewer parse failure mode where malformed LLM EDN still read
successfully because unescaped quoted prose became extra symbol keys inside a
`:review/issues` map. The review artifact then carried structurally corrupt
issue data into checkpoint feedback instead of failing closed.

This change validates parsed LLM review issues before accepting a review map.
Issue maps must now match the canonical `ReviewIssue` shape and only contain
recognized issue keys.

## Changes

- Reject parsed LLM review maps whose `:review/issues` value is not a vector of
  canonical issue maps.
- Add a regression test for the dogfood failure shape where quoted prose is
  parsed as stray EDN symbols.
- Keep valid parseable review content working even when the backend wrapper
  reports failure.

## Verification

```bash
clojure -M:dev:test -e "(require 'ai.miniforge.agent.reviewer-test 'clojure.test) (clojure.test/run-tests 'ai.miniforge.agent.reviewer-test)"
```
