# Changelog

All notable changes to Miniforge are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [Unreleased]

### Added

- TUI supervisory control plane: normative delta, work spec, and fleet deferred items
- PR monitor loop: autonomous comment resolution and change iteration (Observe phase)
- Control plane: multi-vendor agent registry, heartbeat watchdog, decision queue
- Deployment pack: provision, deploy, validate, shell, safety gates, evidence
- DAG orchestrator: parallel task execution with resumability and rate-limit resilience
- Reliability network (RN-01 through RN-16): failure taxonomy, SLI/SLO engine, autonomy model, compensation protocol

### Fixed

- DAG orchestrator: non-rate-limited failures dropped when batch contains rate-limited tasks
- DAG orchestrator: unreached tasks not counted in success predicate
- Release phase: design gap in artifact submission path
- MCP server crash on malformed tool calls
- Implementer agent: artifact recovery from workspace writes

### Changed

- TUI paradigm shift: monitor-first supervisory surface over human command interface
- Workflow runner acquires isolated execution environment per sub-workflow
- Standard SDLC pipeline (v2.0.0): interceptor-based, ~30 lines replacing 145+

## [0.1.0] — 2026-01-01

Initial internal release.

- Standard SDLC workflow: plan → implement → verify → review → release → observe
- Agent runtime: planner, implementer, reviewer, tester, releaser
- Policy gates: syntax, lint, no-secrets, tests-pass, coverage, review-approved
- Event stream with append-only semantics and file subscription
- CLI (`mf run`, `mf workflow`, `mf tui`) via Babashka
- Web dashboard with htmx live updates
- Polylith monorepo architecture
