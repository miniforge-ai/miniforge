# feat: Add LLM-backed semantic code review to reviewer agent

## Overview

Upgrades the reviewer agent from gate-only deterministic checks to
LLM-backed semantic code review plus deterministic gate validation.
Fixes two structural bugs: the reviewer returning a plain map instead
of a `FunctionalAgent` record (breaking protocol dispatch), and the
review phase passing stub gate maps instead of real Gate implementations.

## Motivation

During dogfooding, the review phase completed in ~1ms with no semantic
analysis — it only ran deterministic gates (syntax, lint, policy). A
human reviewer immediately spotted quality issues (missing edge cases,
non-idiomatic code) that the workflow missed entirely. The reviewer
agent needs LLM-backed semantic review so the workflow catches these
issues before release.

Additionally, two bugs prevented the reviewer from working correctly
via protocol dispatch:

1. `create-reviewer` returned a plain map, not a `FunctionalAgent`
   record — `agent/invoke` uses protocol dispatch which fails on
   plain maps
2. The review phase passed stub gate maps
   `{:gate/id :x :gate/type :x}` instead of real `Gate` protocol
   implementations

## Changes in Detail

### New file: `components/agent/resources/prompts/reviewer.edn`

- System prompt for LLM code review following the
  implementer/planner pattern
- Instructs the LLM to review for correctness, edge cases,
  idiomatic style, error handling, design, and security
- Defines structured output format: decision, issues
  (with severity/file/line), summary, strengths

### Major refactor: `components/agent/src/ai/miniforge/agent/reviewer.clj`

- **Protocol fix**: Uses `specialized/create-base-agent` to return
  `FunctionalAgent` record
- **LLM integration**: Calls `llm/chat` or `llm/chat-stream` for
  semantic review with streaming support
- **New schemas**: `ReviewIssue` for structured issues; updated
  `ReviewArtifact` with `:review/issues`, `:review/strengths`,
  `:changes-requested` decision
- **Decision merging**: LLM does semantic review, gates do
  deterministic checks, gate failures can override LLM approval
- **Backwards compatible**: Falls back to gate-only review when no
  `llm-backend` is available
- **New utilities**: `changes-requested?`, `get-issues`,
  `get-strengths`

### Minor update: `components/phase/src/ai/miniforge/phase/review.clj`

- Removed stub gate map construction — reviewer now manages its
  own gates internally
- Passes `llm-backend` from execution context to `create-reviewer`
- Fixed artifact extraction:
  `(or (:artifact implement-result) implement-result)`
- Increased token budget from 10,000 to 20,000

### Docstring update: `components/agent/src/ai/miniforge/agent/interface.clj`

- Updated `create-reviewer` docstring to reflect LLM + gates
- Added `ReviewIssue` schema export
- Added `changes-requested?`, `get-review-issues`,
  `get-review-strengths` utility exports

## Testing Plan

- Run existing tests: `cd miniforge-tui && clojure -M:test`
- Run the algorithms spec workflow:
  `clojure -M:dev -m ai.miniforge.cli.main run work/algorithms-tests.spec.edn`
- Verify review phase now takes >1ms and produces semantic
  feedback in event stream
- Verify review phase still works without LLM backend
  (gate-only fallback)
- Check event file (`~/.miniforge/events/<id>.edn`) for review
  phase events with LLM content

## Deployment Plan

Standard merge to main. No infrastructure changes needed — the LLM
backend is already available in the execution context from other
agents.

## Related Issues/PRs

- Discovered during dogfooding of the chain workflow system
- Follows patterns established by implementer agent (PR #216+)

## Checklist

- [x] Reviewer returns `FunctionalAgent` record (protocol dispatch fix)
- [x] Review phase no longer passes stub gate maps
- [x] LLM review produces structured issues with severity levels
- [x] Gate failures can override LLM approval
- [x] Backwards compatible — gate-only fallback when no LLM client
- [x] Streaming support via `:on-chunk`
- [x] System prompt follows existing EDN pattern
- [x] Interface exports updated with new schemas and utilities
- [ ] Existing tests pass
- [ ] End-to-end workflow test with LLM review
