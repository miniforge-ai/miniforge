# Workflow Specification Examples

This directory contains example workflow specifications for miniforge.

## Usage

Run a workflow from a spec file:

```bash
mf run examples/workflows/simple-refactor.edn
mf run examples/workflows/implement-feature.edn
mf run examples/workflows/test-simple-function.edn
```

## Pipeline Format (v1.0.0)

All workflows now use the standardized **pipeline format**. This format provides:

- Clear versioning and type discrimination
- Structured task specification
- Support for different workflow types (full-sdlc, test-only, etc.)
- Consistent validation and execution semantics

### Required Fields

```clojure
{:workflow/spec-version "1.0.0"    ; Required: spec format version
 :workflow/type :full-sdlc         ; Required: workflow type
 :workflow/version "2.0.0"         ; Required: workflow execution version

 :task/title "..."                 ; Required: human-readable task title
 :task/description "..."           ; Required: detailed task description
 :task/intent "..."                ; Required: structured intent
 :task/constraints [...]           ; Required: constraint list
 :task/acceptance-criteria [...]}  ; Optional: success criteria
```

### Workflow Types

- `:full-sdlc` - Complete software development lifecycle
  - Phases: plan → implement → verify → review → release
  - Use for: new features, refactoring, bug fixes

- `:test-only` - Test generation only
  - Phases: verify only
  - Use for: adding tests to existing code
  - Requires: `:task/code-artifact` with code to test

### Field Descriptions

- **:workflow/spec-version** - Always "1.0.0" for current format
- **:workflow/type** - Determines which phases execute (`:full-sdlc` or `:test-only`)
- **:workflow/version** - Workflow execution engine version (currently "2.0.0")
- **:task/title** - Brief, descriptive title (50-80 characters recommended)
- **:task/description** - Detailed explanation of what should be accomplished
- **:task/intent** - Why this task matters and what it achieves (goals, benefits)
- **:task/constraints** - Vector of strings describing requirements and limitations
- **:task/acceptance-criteria** - Vector of strings defining success (optional but recommended)
- **:task/code-artifact** - For test-only workflows: code to generate tests for

### Full SDLC Example

```clojure
;; examples/workflows/simple-refactor.edn
{:workflow/spec-version "1.0.0"
 :workflow/type :full-sdlc
 :workflow/version "2.0.0"

 :task/title "Refactor logging component"
 :task/description
 "Extract structured logging helpers to a separate module for better reusability"

 :task/intent
 "Improve code organization by consolidating scattered logging utilities"

 :task/constraints
 ["No breaking changes to existing public API"
  "Maintain 100% test coverage"
  "Follow polylith architecture rules"]

 :task/acceptance-criteria
 ["Logging utilities consolidated in components/logging/src"
  "All components use the new logging module"
  "Existing tests pass without modification"
  "Polylith validation passes"]}
```

### Test-Only Example

```clojure
;; examples/workflows/test-simple-function.edn
{:workflow/spec-version "1.0.0"
 :workflow/type :test-only
 :workflow/version "2.0.0"

 :task/title "Generate tests for string utility functions"
 :task/description
 "Create comprehensive tests for string manipulation functions"

 :task/intent
 "Generate unit tests covering all edge cases including nil, empty strings, etc."

 :task/constraints
 ["Use clojure.test framework"
  "Test all edge cases"
  "Aim for 100% code coverage"]

 ;; Code artifact with actual content
 :task/code-artifact
 {:code/id #uuid "12345678-1234-1234-1234-123456789abc"
  :code/language "clojure"
  :code/files
  [{:path "src/utils/string.clj"
    :action :test-existing
    :content "(ns utils.string ...) ..."}]}

 :task/acceptance-criteria
 ["Tests verify nil handling"
  "Tests cover all edge cases"
  "All tests pass when run"]}
```

## Workflow Execution

When you run a workflow from a spec file, miniforge:

1. **Parses and validates** the spec file against the pipeline schema
2. **Creates workflow context** with:
   - Provenance: source file, format, timestamp
   - Environment: cwd, git info, files-in-scope
   - Metadata: session-id, iteration, parent-task-id
3. **Executes phases** based on workflow type:
   - `:full-sdlc`: plan → implement → verify → review → release
   - `:test-only`: verify only (test generation)
4. **Generates evidence bundle** documenting execution:
   - All phase results and artifacts
   - Agent decisions and reasoning
   - Metrics (tokens, duration, costs)
   - Enriched spec with provenance

## Available Examples

### simple-refactor.edn

Basic refactoring task demonstrating full SDLC workflow with clear intent and constraints.

### implement-feature.edn

Feature implementation workflow aligned with N3 specification requirements. Shows comprehensive acceptance criteria.

### test-simple-function.edn

Minimal test-only workflow demonstrating test generation for existing code with inline code artifact.

### test-phase-files.edn

Advanced test-only workflow showing test generation for multiple files (phase interceptors).

### add-tests-to-phase-files.edn

Real-world example: generating tests for code that miniforge generated for itself (meta-meta loop).

## Creating Your Own Workflows

1. **Copy a template**

   ```bash
   cp examples/workflows/simple-refactor.edn my-workflow.edn
   ```

2. **Update required fields**
   - Change `:task/title` to describe your task
   - Write detailed `:task/description`
   - Clarify `:task/intent` (why this matters)
   - List `:task/constraints` (requirements, limitations)
   - Add `:task/acceptance-criteria` (how to measure success)

3. **Choose workflow type**
   - Use `:full-sdlc` for implementation tasks
   - Use `:test-only` for test generation
   - Ensure `:task/code-artifact` is present for test-only

4. **Run your workflow**

   ```bash
   mf run my-workflow.edn
   ```

## Migration from Old Format

If you have workflows in the old format (simple EDN/JSON without `:workflow/*` fields):

1. Add required workflow fields:
   - `:workflow/spec-version "1.0.0"`
   - `:workflow/type :full-sdlc`
   - `:workflow/version "2.0.0"`

2. Rename top-level fields to `:task/*` namespace:
   - `:title` → `:task/title`
   - `:description` → `:task/description`
   - `:intent` → `:task/intent` (flatten to string)
   - `:constraints` → `:task/constraints`
   - `:tags` → (remove, no longer used)

3. Add `:task/acceptance-criteria` if applicable

See the examples in this directory for reference implementations.

## Next Steps

- See [miniforge documentation](../../docs/) for more details
- Check [N2 Workflow Execution Model](../../specs/normative/N2-workflows.md) for phase details
- Explore [N6 Evidence & Provenance](../../specs/normative/N6-evidence-provenance.md) for outputs
- Try running `mf run --help` for CLI options
