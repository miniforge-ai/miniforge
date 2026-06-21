# chore: audit function-local require patterns

## Overview

This PR audits the remaining function-local `require` patterns after the
resolver remediation waves and remediates any production cases that are not
intentional loader or REPL/comment boundaries.

## Motivation

The broad resolver cleanup left several `require` forms that need
case-by-case stratification review. Some are legitimate runtime loaders, some
are rich-comment examples, and any production helpers that only use local
requires for convenience should move to normal namespace composition.

## Changes in Detail

- Classify remaining function-local `require` forms in production source.
- Confirm no non-loader production dependencies remain hidden behind
  function-local `require` forms.
- Clarify the intentionally retained phase and test-discovery loader
  boundaries in code docstrings.
- Document any intentionally retained dynamic loader boundaries.

## Audit Results

Retained live loader boundaries:

| Location | Disposition |
| --- | --- |
| `bases/cli/src/ai/miniforge/cli/main.clj` | Intentional optional product-composition boundary for web-dashboard/TUI components not present on every CLI product classpath. Already documented by PR #1230. |
| `components/phase/src/ai/miniforge/phase/loader.clj` | Intentional runtime phase implementation loader driven by classpath EDN resources. Docstring clarified in this PR. |
| `components/bb-test-runner/src/ai/miniforge/bb_test_runner/core.cljc` | Intentional test namespace discovery boundary. Docstring clarified in this PR. |

All other `src` hits from the scan were rich-comment examples or docstring
usage snippets, not production execution paths.

## Testing Plan

- Run focused tests for touched namespaces.
- Run `bb pre-commit` before commit.
- Let pull request CI run the stable-derived test plan.

## Deployment Plan

No special deployment steps. This is an internal standards cleanup.

## Related Issues/PRs

- Follows PR #1244 and the earlier resolver remediation waves.

## Checklist

- [x] Classify remaining production function-local requires.
- [x] Apply scoped remediations.
- [x] Run local validation.
- [ ] Open PR and address review comments.
