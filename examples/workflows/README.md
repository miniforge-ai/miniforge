# Workflow Specification Examples

This directory contains example workflow specifications for miniforge.

## Usage

Run a workflow from a spec file:

```bash
mf run examples/workflows/simple-refactor.edn
mf run examples/workflows/implement-feature.edn
mf run examples/workflows/test-simple-function.edn
```

## Spec Format

Workflow specs use **fully-namespaced `:spec/*` keywords** to describe user intent.
The spec parser validates input against a Malli schema (`SpecInput`)
and normalizes to a `SpecPayload` with defaults applied.

### Canonical Format

```clojure
{;; === Required ===
 :spec/title              "One-line summary"
 :spec/description        "Detailed prose description..."

 ;; === Recommended ===
 :spec/intent             "What kind of work and why..."
 :spec/constraints        ["constraint-1" "constraint-2"]
 :spec/acceptance-criteria ["criterion-1" "criterion-2"]

 ;; === Optional ===
 :spec/tags               [:tag1 :tag2]
 :spec/code-artifact      {:code/id ... :code/files [...]}  ; for test-only workflows

 ;; === Workflow selection ===
 :workflow/type            :full-sdlc
 :workflow/version         "2.0.0"

 ;; === Pre-decomposed plan (optional) ===
 :spec/plan-tasks         [{:task/id :do-thing
                            :task/description "..."
                            :task/type :implement
                            :task/dependencies []}]

 ;; === Operational overrides ===
 :spec/repo-url           "https://..."     ; git repo URL
 :spec/branch             "feat/my-branch"  ; target branch
 :spec/llm-backend        :anthropic        ; LLM provider override
 :spec/sandbox            true              ; run in Docker sandbox

 ;; === Free-form context (any unnamespaced keys pass through) ===
 :context                 {:supporting-docs ["..."] :real-world-example "..."}
 :diagnostic-steps        ["step 1" "step 2"]
 ;; ... any additional keys agents may need
 }
```

### Domain Namespaces

| Domain | Namespace | Who Authors | What It Represents |
|--------|-----------|-------------|-------------------|
| **Spec** | `:spec/*` | Users (via prose or EDN) | Intent -- what the user wants and why |
| **Task** | `:task/*` | System (planner output) | Work units -- decomposed executable items |
| **Workflow** | `:workflow/*` | System (config files) | Process -- how the system executes |

A spec is not a task. A spec expresses intent and constraints. The system
decomposes specs into tasks.

### Workflow Types

- `:full-sdlc` - Complete software development lifecycle
  - Phases: plan, implement, verify, review, release
  - Use for: new features, refactoring, bug fixes

- `:test-only` - Test generation only
  - Phases: verify only
  - Use for: adding tests to existing code
  - Requires: `:spec/code-artifact` with code to test

### Full SDLC Example

```clojure
;; examples/workflows/simple-refactor.edn
{:workflow/spec-version "1.0.0"
 :workflow/type :full-sdlc
 :workflow/version "2.0.0"

 :spec/title "Refactor logging component"
 :spec/description
 "Extract structured logging helpers to a separate module for better reusability"

 :spec/intent
 "Improve code organization by consolidating scattered logging utilities"

 :spec/constraints
 ["No breaking changes to existing public API"
  "Maintain 100% test coverage"
  "Follow polylith architecture rules"]

 :spec/acceptance-criteria
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

 :spec/title "Generate tests for string utility functions"
 :spec/description
 "Create comprehensive tests for string manipulation functions"

 :spec/intent
 "Generate unit tests covering all edge cases including nil, empty strings, etc."

 :spec/constraints
 ["Use clojure.test framework"
  "Test all edge cases"
  "Aim for 100% code coverage"]

 ;; Code artifact with actual content
 :spec/code-artifact
 {:code/id #uuid "12345678-1234-1234-1234-123456789abc"
  :code/language "clojure"
  :code/files
  [{:path "src/utils/string.clj"
    :action :test-existing
    :content "(ns utils.string ...) ..."}]}

 :spec/acceptance-criteria
 ["Tests verify nil handling"
  "Tests cover all edge cases"
  "All tests pass when run"]}
```

## Workflow Execution

When you run a workflow from a spec file, miniforge:

1. **Parses and validates** the spec file against the pipeline schema
2. **Normalizes** the spec to canonical `:spec/*` format with defaults
3. **Creates workflow context** with:
   - Provenance: source file, format, timestamp
   - Environment: cwd, git info, files-in-scope
   - Metadata: session-id, iteration, parent-task-id
4. **Executes phases** based on workflow type:
   - `:full-sdlc`: plan, implement, verify, review, release
   - `:test-only`: verify only (test generation)
5. **Generates evidence bundle** documenting execution:
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
   - Change `:spec/title` to describe your task
   - Write detailed `:spec/description`
   - Clarify `:spec/intent` (why this matters)
   - List `:spec/constraints` (requirements, limitations)
   - Add `:spec/acceptance-criteria` (how to measure success)

3. **Choose workflow type**
   - Use `:full-sdlc` for implementation tasks
   - Use `:test-only` for test generation
   - Ensure `:spec/code-artifact` is present for test-only

4. **Run your workflow**

   ```bash
   mf run my-workflow.edn
   ```

## Next Steps

- See [miniforge documentation](../../docs/) for more details
- Check [N2 Workflow Execution Model](../../specs/normative/N2-workflows.md) for phase details
- Explore [N6 Evidence & Provenance](../../specs/normative/N6-evidence-provenance.md) for outputs
- Try running `mf run --help` for CLI options
