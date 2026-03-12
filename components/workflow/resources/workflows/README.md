# Workflow Catalog

This directory contains the workflow definitions that ship with the shared
`workflow` component.

These workflows are intended to be domain-neutral examples, local validation
flows, and reference implementations for the generic workflow runtime.

Software-factory workflow families such as the SDLC flows now live in
`components/workflow-software-factory/resources/workflows/`.

## Workflow Index

| Workflow ID | Version | Purpose | Notes |
|-------------|---------|---------|-------|
| `financial-etl` | 1.0.0 | Reference analytical workflow for staged ETL and evaluation | Exercises non-software vertical seams |
| `simple-v2` | 2.0.0 | Lightweight general workflow for local execution | Good default example for demos |
| `simple-test-v1` | 1.0.0 | Integration testing and tutorial flow | Minimal multi-phase example |
| `minimal-test-v1` | 1.0.0 | Unit and smoke testing flow | Single-phase baseline |

## What Belongs Here

- Domain-neutral workflow examples
- Test and validation workflows used by the shared runtime
- Reference vertical demos that prove the kernel is not software-only

## What Does Not Belong Here

- Software-factory workflow families
- App-specific workflow defaults or selection policy
- Premium or marketplace workflow packs

## Notes

- Workflows are discovered from every `workflows/` directory on the active
  classpath.
- The final visible catalog depends on which components are loaded by the
  current project or base.
- For flagship software-factory workflows, inspect the
  `workflow-software-factory` component instead of this directory.
