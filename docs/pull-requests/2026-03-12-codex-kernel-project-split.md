# Kernel Project Split

## Layer

Infrastructure

## Depends on

- #305 (`codex/etl-runtime-split`) — merged

## What this adds

- removes product-owned workflow resources from the shared CLI base
- adds a kernel-only `workflow-kernel` project composition
- adds integration coverage proving the kernel project classpath only ships kernel workflows

## Strata affected

- `bases/cli` — base no longer depends on software-factory or ETL components
- `projects/workflow-kernel` — new kernel/local-runtime project composition
- `tasks/build.clj`, `bb.edn`, `tasks/test_runner.clj` — build and integration tooling for the new project

## Verification

- `bb test`
- `bb test:integration`
- `bb build:kernel`
- `bb build:cli`
- `bb build:tui`
- `bb pre-commit`
