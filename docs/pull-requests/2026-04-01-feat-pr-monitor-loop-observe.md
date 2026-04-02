# feat: pr monitor loop observe

## Layer

Integration

## Depends on

- `feat/pr-monitor-loop-core`

## Overview

Wires the PR monitor loop into the workflow observe phase and the public pr-lifecycle interface.

## Motivation

The monitor stack is only useful once the workflow layer can invoke it and downstream callers can reach the new API from
the component interface.

## Changes in Detail

- Add `observe_phase.clj`.
- Export the PR monitor API from `pr-lifecycle/interface.clj`.
- Update workflow loader and SDLC workflow resources to include observe-phase wiring.

## Testing Plan

- Run `bb pre-commit`

## Deployment Plan

- Merge last in the PR monitor loop stack.

## Related Issues/PRs

- Parent feature: PR monitor loop

## Checklist

- [x] Scope limited to integration and public API wiring
- [ ] `bb pre-commit` recorded
