<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Documentation Index

## Getting Started

- **[Quickstart](quickstart.md)** -- Clone, bootstrap, run your first workflow in 5 minutes
- **[Writing Specs](user-guide/writing-specs.md)** -- Task format, EDN syntax, acceptance criteria
- **[Configuration](user-guide/configuration.md)** -- Environment, LLM backends, cost limits
- **[Demo Walkthrough](demo.md)** -- Guided example from spec to pull request

## Using Miniforge

- **[Phase Pipeline](user-guide/phases.md)** -- Explore, Plan, Implement, Verify, Review, Release, Observe
- **[Architecture Overview](user-guide/architecture.md)** -- Components, layers, dependency flow
- **[Deployment](deployment.md)** -- Babashka CLI + jlink TUI, installation, configuration
- **[GitHub App Setup](GITHUB_APP_SETUP.md)** -- OAuth configuration for PR automation
- **[Model Selection](MODEL_SELECTION.md)** -- 16 models across 4 providers, task-based routing

## Contributing

- **[Contributing Guide](../CONTRIBUTING.md)** -- Fork, branch, test, PR process
- **[Engineering Standards](development-guidelines.md)** -- Stratified design, refactoring discipline
- **[CI Debugging](development/ci-debugging.md)** -- Troubleshoot test and lint failures
- **[Workflow Implementation Guide](guides/workflow-implementation.md)** -- Adding new workflows

## Architecture and Design

- **[Orchestrator Architecture](design/orchestrator-architecture.md)** -- Coordination, phase transitions, event routing
- **[Knowledge Component](design/knowledge-component.md)** -- Symbol resolution, trust model, caching
- **[Policy Pack Taxonomy](design/policy-pack-taxonomy.md)** -- Pack classification, lifecycle, governance
- **[Compliance Scanner](design/compliance-scanner.md)** -- Scan, classify, plan, execute
- **[Quality Readiness](design/quality-readiness.md)** -- Quality gates and readiness criteria
- **[Workflow Methodologies](design/workflow-methodologies.md)** -- SDLC, ETL, governance patterns
- **[Meta-Agents](meta-agents.md)** -- Health checks, progress monitoring, adaptive timeouts

## Research

- **[Open-Core Boundary Review](research/open-core-boundary-review.md)** -- OSS vs enterprise analysis
- **[Repo Split Proposal](research/repo-split-proposal.md)** -- Three-product architecture
- **[ETL Vertical Fit](research/etl-vertical-fit.md)** -- Data Foundry as second product
- **[Workflow Optimization Theory](research/workflow-optimization-theory.md)** -- Cost, throughput, repair cycles

## Operations

- **[Release Procedure](RELEASE.md)** -- Version bumping, changelog, artifact publication
- **[Configuration Reference](configuration.md)** -- Full environment and config file reference

## Status

- **[Roadmap](../ROADMAP.md)** -- Normative spec progress (N1-N11), priorities, timeline
- **[Progress Review (2026-04-13)](progress-review-2026-04-13.md)** -- Spec completeness audit
- **[Gap Analysis (N1-N9)](gap-analysis-n1-n9.md)** -- Detailed requirement audit (Feb 2026)

## Specifications

See **[specs/SPEC_INDEX.md](../specs/SPEC_INDEX.md)** for the normative contract (N1-N11),
informative guidance, and conformance requirements.

## Archive

Historical logs and completed project snapshots are in [docs/archive/](archive/).
