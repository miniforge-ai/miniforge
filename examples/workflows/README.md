# Workflow Specification Examples

This directory contains example workflow specifications for miniforge.

## Usage

Run a workflow from a spec file:

```bash
mf run examples/workflows/simple-refactor.edn
mf run examples/workflows/add-tests.json
mf run examples/workflows/implement-feature.edn
mf run examples/workflows/fix-bug.md
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

### Markdown Example

Markdown format uses YAML frontmatter for structured data and supports extended description in the body:

```markdown
---
title: Fix authentication timeout bug
description: Resolve intermittent timeout errors in JWT validation
intent:
  type: bugfix
  scope: [components/auth/src]
constraints:
  - maintain-backward-compatibility
  - add-regression-test
tags: [bugfix, authentication]
---

# Additional Context

Extended description, references, and context can go here in the body.
The body content is appended to the description field.
```

## Supported Formats

- `.edn` - Clojure EDN (recommended for programmatic generation)
- `.json` - JSON (recommended for tool integration)
- `.md` - Markdown with YAML frontmatter (recommended for human authoring)
- `.yaml` - YAML (coming soon)

## Workflow Execution

When you run a workflow from a spec file, miniforge:

1. **Parses and validates** the spec file (EDN/JSON/Markdown)
2. **Decorates** the spec with context (using interceptor pattern):
   - Layer 0 (parse time): `:spec/provenance` - source file, format, timestamp
   - Layer 1 (execution time): `:spec/context` - cwd, git info, files-in-scope
   - Layer 1 (execution time): `:spec/metadata` - session-id, iteration, parent-task-id
   - Layer 2 (future): `:spec/learning-refs` - meta-loop injected learnings
3. **Creates** an ad-hoc workflow based on the canonical SDLC workflow
4. **Executes** the workflow phases: Plan → Design → Implement → Verify → Review → Release → Observe
5. **Generates** an evidence bundle documenting the execution (includes enriched spec)

## Examples

### simple-refactor.edn

Basic refactoring task with clear intent and constraints.

### add-tests.json

Testing workflow demonstrating JSON format.

### implement-feature.edn

Feature implementation aligned with N3 specification requirements.

### fix-bug.md

Bug fix workflow demonstrating Markdown format with YAML frontmatter and extended context in the body.

## Creating Your Own Specs

1. Copy one of the examples as a template
2. Modify the title, description, and intent to match your task
3. Add relevant constraints and tags
4. Run with `mf run your-spec-file.edn`

## Next Steps

- See the [miniforge documentation](../../docs/) for more details
- Check the [N2 Workflow Execution Model spec](../../specs/normative/N2-workflows.md) for workflow phase details
- Explore [N6 Evidence & Provenance](../../specs/normative/N6-evidence-provenance.md) for understanding workflow outputs
