# Workflow Specification Examples

This directory contains example workflow specifications for miniforge.

## Usage

Run a workflow from a spec file:

```bash
mf run examples/workflows/simple-refactor.edn
mf run examples/workflows/add-tests.json
mf run examples/workflows/implement-feature.edn
```

## Spec Format

Workflow specs can be written in EDN or JSON format.

### Required Fields

- `title` - Human-readable workflow title
- `description` - Detailed description of what the workflow should accomplish

### Optional Fields

- `intent` - Structured intent specification
  - `type` - Intent type (`:refactor`, `:feature`, `:bugfix`, `:testing`, etc.)
  - `scope` - List of paths or components in scope
- `constraints` - List of constraints the workflow must satisfy
- `tags` - List of tags for categorization

### EDN Example

```clojure
{:title "Refactor logging component"
 :description "Extract structured logging helpers to a separate module"
 :intent {:type :refactor
          :scope ["components/logging/src"]}
 :constraints ["no-breaking-changes"
               "maintain-test-coverage"]
 :tags [:refactoring :code-quality]}
```

### JSON Example

```json
{
  "title": "Add test coverage",
  "description": "Increase test coverage for the knowledge component",
  "intent": {
    "type": "testing",
    "scope": ["components/knowledge/test"]
  },
  "constraints": [
    "achieve-80-percent-coverage"
  ],
  "tags": ["testing", "quality"]
}
```

## Supported Formats

- `.edn` - Clojure EDN (recommended)
- `.json` - JSON
- `.yaml` - YAML (coming soon)

## Workflow Execution

When you run a workflow from a spec file, miniforge:

1. Parses and validates the spec file
2. Creates an ad-hoc workflow based on the canonical SDLC workflow
3. Executes the workflow phases: Plan → Design → Implement → Verify → Review → Release → Observe
4. Generates an evidence bundle documenting the execution

## Examples

### simple-refactor.edn

Basic refactoring task with clear intent and constraints.

### add-tests.json

Testing workflow demonstrating JSON format.

### implement-feature.edn

Feature implementation aligned with N3 specification requirements.

## Creating Your Own Specs

1. Copy one of the examples as a template
2. Modify the title, description, and intent to match your task
3. Add relevant constraints and tags
4. Run with `mf run your-spec-file.edn`

## Next Steps

- See the [miniforge documentation](../../docs/) for more details
- Check the [N2 Workflow Execution Model spec](../../specs/normative/N2-workflows.md) for workflow phase details
- Explore [N6 Evidence & Provenance](../../specs/normative/N6-evidence-provenance.md) for understanding workflow outputs
