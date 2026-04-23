# feat: formalize workflow, supervision, and operator-loop specs

## Overview

Formalize the architectural split between workflow execution, live supervision,
global degradation mode, the learning/meta loop, and the human supervisory
loop. Amend the normative specs so the intended control model is explicit
before code migration begins.

## Motivation

The current implementation and specs mostly imply the right separation, but the
boundaries are not explicit enough to safely refactor toward formal state
machines without leaving competing authorities behind. In particular:

- workflow execution currently mixes execution status and phase-transition logic
- live supervisory checks and learning/meta-loop responsibilities are blurred
- the human supervisory role is present in the N5 control-plane material but
  not yet tied cleanly to the orchestrator and machine model
- resume semantics still reflect phase-index thinking rather than machine
  snapshot restoration

This PR establishes the contract that the code migration will follow.

## Changes In Detail

- Amend N1 to separate:
  - control-plane orchestration
  - workflow supervision
  - learning/meta-loop improvement
  - human supervisory loop
- Amend N2 to define:
  - a unified execution machine per workflow run
  - a distinct supervisory machine for live governance and intervention
  - event-driven phase outcomes instead of imperative phase steering
  - machine-snapshot-oriented resume semantics
- Amend the N5 supervisory control-plane delta to formalize:
  - the human supervisory loop as a peer runtime to the orchestrator
  - bounded intervention lifecycle semantics
  - command/query separation against canonical supervisory state
- Add an informative architecture note describing the target multi-machine,
  peer-runtime model that subsequent implementation PRs will follow

## Testing Plan

- Read the updated spec set end to end for internal consistency:
  - N1 core architecture
  - N2 workflows
  - N2 phase checkpoint/resume delta
  - N5 supervisory control-plane delta
- Verify terminology is consistent across:
  - orchestrator
  - supervisory loop
  - meta loop
  - degradation mode
  - workflow execution machine
- Run Markdown lint/format checks if needed

## Deployment Plan

No runtime deployment. This is a contract-first documentation PR that should
merge before implementation PRs that migrate workflow and supervisory control
logic onto formal machines.

## Related Issues/PRs

- Prepares the ground for the workflow/machine compiler refactor
- Prepares the ground for supervisory-state and intervention lifecycle work
- Follow-up implementation PRs should stack on this one

## Checklist

- [x] N1 updated with orchestrator vs supervisory vs meta-loop boundaries
- [x] N2 updated with unified execution-machine model
- [x] N2 resume delta updated for machine snapshot semantics
- [x] N5 supervisory delta updated for operator supervisory loop and interventions
- [x] Informative architecture note added
- [x] Spec language cross-checked for consistency
