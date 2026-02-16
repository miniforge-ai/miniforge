# refactor: standardize spec EDN on `:spec/*` keywords

## Layer

Domain Model + Component

## Depends on

- None

## Overview

Migrate all spec EDN files and their consumers to a single canonical `:spec/*`
namespace. Extract the spec parser from the CLI base into a proper Polylith
component (`spec-parser`) with Malli schemas for validation.

## Motivation

Spec files evolved organically and accumulated mixed keyword styles. The parser
was hard-coded in the CLI base, making it difficult to add new formats. No
backward compat was needed since there are no external users.

## What this changes

### 1. New `components/spec-parser/` Polylith component

Extracted from the CLI base into a proper component with:

- **`schema.clj`** — Malli schemas: `SpecInput`, `SpecPayload`, `SpecIntent`,
  `PlanTask`, `CodeArtifact`, `SpecProvenance`
- **`core.clj`** — Format detection, format-specific parsers (EDN, JSON, Markdown),
  normalization, file parsing. Parsers registered in a `format-parsers` map for
  extensibility.
- **`interface.clj`** — Public API: `parse-spec-file`, `normalize-spec`,
  `validate-spec`, `valid-spec-input?`, `valid-spec-payload?`, schema re-exports
- **`deps.edn`** — malli, babashka/fs, cheshire

### 2. Simplified normalizer (no compat layers)

- Removed triple-or extraction (`(:spec/x spec) (:task/x spec) (:x spec)`)
- Removed deprecation warning for `:task/*` keys
- Uses Clojure destructuring: `{:spec/keys [title description ...] :as spec}`
- Only accepts canonical `:spec/*` input format

### 3. CLI base becomes thin delegation

`spec_parser.clj` in the CLI base now just requires and delegates to
`ai.miniforge.spec-parser.interface`. Existing callers don't change.

### 4. Malli-based validation

- `validate-spec` now uses the `SpecPayload` Malli schema instead of hand-rolled checks
- `SpecInput` schema validates raw input before normalization
- Tests use `malli.generator/generate` for round-trip validation

### 5. EDN file migration

All 36 example and work spec files migrated to canonical `:spec/*` keywords.

## Changes in Detail

### New files

- `components/spec-parser/deps.edn`
- `components/spec-parser/src/ai/miniforge/spec_parser/schema.clj`
- `components/spec-parser/src/ai/miniforge/spec_parser/core.clj`
- `components/spec-parser/src/ai/miniforge/spec_parser/interface.clj`
- `components/spec-parser/test/ai/miniforge/spec_parser/interface_test.clj`

### Modified files

- `bases/cli/src/ai/miniforge/cli/spec_parser.clj` — thin delegation to component
- `bases/cli/src/ai/miniforge/cli/workflow_runner.clj` — use `:spec/sandbox`
- `bases/cli/src/ai/miniforge/cli/workflow_runner/sandbox.clj` — use `:spec/repo-url`, `:spec/branch`
- `bases/cli/src/ai/miniforge/cli/workflow_runner/context.clj` — use promoted `:spec/*` keys
- `bases/cli/src/ai/miniforge/cli/workflow_selector.clj` — simplified extraction
- `bases/cli/src/ai/miniforge/cli/workflow_recommender.clj` — simplified title lookup
- `components/web-dashboard/src/ai/miniforge/web_dashboard/archive.clj` — `:spec/title` lookup
- `components/web-dashboard/src/ai/miniforge/web_dashboard/state/workflows.clj` — `:spec/title` lookup
- `projects/miniforge/deps.edn` — added `spec-parser` component
- `examples/workflows/README.md` — removed migration guide, updated architecture
- `examples/workflows/*.edn` (16 files) — keyword migration
- `work/*.edn` + `work/archive/*.edn` (20 files) — keyword migration

### Deleted files

- `bases/cli/test/ai/miniforge/cli/spec_parser_test.clj` — replaced by component tests

## Testing Plan

- `bb pre-commit` — all checks pass (lint, format, poly test, GraalVM compat)
- New `interface_test.clj` covers: Malli schema validation, normalization,
  defaults, error cases, Malli generator round-trips
- Existing `workflow_selector_test.clj` and `file_writing_test.clj` pass unchanged
