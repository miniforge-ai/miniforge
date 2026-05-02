# feat: surface dependency attribution in CLI and dashboard workflows

## Summary

This PR makes dependency-health projection user-visible in the workflow UX.

It builds on the dependency event/degradation slice and:

- derives canonical active dependency issues from workflow event history in the dashboard state layer
- shows dependency attribution in the workflow detail panel and workflow list cards
- formats dependency health and recovery events in the CLI workflow runner
- keeps the UI on the canonical dependency-health projection instead of adding another ad hoc failure summary

## Problem

Dogfooding exposed an important attribution gap: when a workflow is blocked by
an LLM vendor, platform, or user-environment dependency, Miniforge still tends
to present that as a generic workflow failure.

That makes it harder to distinguish:

- Miniforge bugs
- external provider outages
- platform unavailability
- misconfiguration or operator-action-required dependency failures

## Changes

### Dashboard state

- added dependency event projection helpers in
  [components/web-dashboard/src/ai/miniforge/web_dashboard/state/workflows.clj](components/web-dashboard/src/ai/miniforge/web_dashboard/state/workflows.clj)
- derive `:dependency-health`, `:dependency-issues`,
  `:dependency-severity`, and `:failure-attribution` from canonical
  dependency events

### Workflow dashboard views

- updated
  [components/web-dashboard/src/ai/miniforge/web_dashboard/views/workflows.clj](components/web-dashboard/src/ai/miniforg
  e/web_dashboard/views/workflows.clj)
- workflow cards now show dependency-issue counts
- workflow detail panels now show dependency attribution and a dependency-health section

### CLI workflow runner

- updated
  [bases/cli/src/ai/miniforge/cli/workflow_runner/display.clj](bases/cli/src/ai/miniforge/cli/workflow_runner/display.clj)
- CLI progress output now formats:
  - `:dependency/health-updated`
  - `:dependency/recovered`

### Localization

- added en-US labels for dependency kind/status in:
  [components/web-dashboard/resources/config/web-dashboard/messages/en-US.edn](components/web-dashboard/resources/config/web-dashboard/messages/en-US.edn)
  [bases/cli/resources/config/cli/messages/en-US.edn](bases/cli/resources/config/cli/messages/en-US.edn)

## Validation

- `clj-kondo --lint` on all touched files
- targeted JVM tests for:
  - `ai.miniforge.cli.workflow-runner.display-output-test`
  - `ai.miniforge.web-dashboard.state.workflows-test`
  - `ai.miniforge.web-dashboard.views.workflows-test`
- full `bb pre-commit`

## Follow-up

The remaining dependency-health stack slice after this carries the same
attribution into workflow/evidence/observe outputs so dogfood and evidence
bundles can distinguish product failures from dependency failures end-to-end.
