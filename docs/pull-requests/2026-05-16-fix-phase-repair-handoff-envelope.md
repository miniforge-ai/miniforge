# Fix: Phase repair handoff envelope

## Overview

Capture typed phase-transition handoff guidance as an informative spec and use
the first repair-focused slice to improve review/implement convergence.

## Motivation

Dogfooding `work/event-log-tool-visibility.spec.edn` showed a repeated
review-to-implement repair loop. Review identified missing acceptance groups,
but subsequent implement attempts did not reliably treat that feedback as
durable structured input.

## Changes in Detail

- Add an informative spec for typed phase handoff envelopes.
- Introduce a narrow repair-request envelope for phase redirects.
- Preserve missing acceptance-group feedback across repair redirects.
- Render the structured repair request into the next implement prompt.
- Add regression coverage for repair handoff preservation.

## Testing Plan

- Focused unit tests for the repair handoff helpers.
- Relevant phase/software-factory tests for review redirect behavior.
- `bb pre-commit` investigation: hook reached `workflow.runner-extended-test`
  and stopped making progress after 300s in the stable-derived sweep.
- Manual validation for this slice:
  - Focused Clojure tests for handoff, review repair loop, implementer prompt,
    and checkpoint persistence.
  - `git diff --check`

## Deployment Plan

Merge normally. The first slice should preserve existing phase transition
behavior while adding structured repair context for retry prompts.

## Related Issues/PRs

- Dogfood checkpoint: `3927baf8-c9db-44d3-b5fb-5a1552dbe554`
- Spec: `work/event-log-tool-visibility.spec.edn`
- Informative companion to normative workflow phase-transition specs.

## Checklist

- [x] Capture informative phase handoff envelope spec.
- [x] Implement narrow repair-request handoff.
- [x] Add regression coverage.
- [x] Run focused validation.
- [ ] Full pre-commit completes without the stable-derived hang.
