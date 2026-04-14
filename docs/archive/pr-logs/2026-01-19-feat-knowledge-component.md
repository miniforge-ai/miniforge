<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# PR: Knowledge Component (Zettelkasten for AI Agents)

**Branch:** `feat/knowledge-component`
**Date:** 2026-01-19

## Summary

Implements a Zettelkasten-style knowledge management system for AI agents. Enables shared knowledge
across all agent roles with explicit link rationales, Dewey classification integration, and learning
capture from execution loops.

## Background

The knowledge component addresses a key architectural need: agents require access to rules, concepts,
learnings, and examples that go beyond siloed "rules files." A proper Zettelkasten provides:

- **Atomic Notes**: Each zettel is one focused idea
- **Explicit Links**: Connections have rationales explaining *why* they're related
- **Hub Notes**: Structure notes organize related zettels
- **Learning Capture**: Insights from inner/meta loops become part of the knowledge base

## Changes

### New Files

- `components/knowledge/deps.edn` - Component dependencies (schema, logging, malli, clj-yaml)
- `components/knowledge/src/ai/miniforge/knowledge/schema.clj` - Malli schemas for:
  - `Zettel` - Atomic knowledge unit with id, uid, title, content, type, dewey, tags, links
  - `Link` - Connection with target-id, type, rationale (minimum 10 chars required)
  - `ZettelType` - :rule, :concept, :learning, :example, :hub, :question, :decision
  - `LinkType` - :supports, :contradicts, :extends, :applies-to, :example-of, etc.
  - `KnowledgeQuery` - Query specification with filters
  - `AgentManifest` - Injection configuration per role
  - `LearningCapture` - Input for capturing learnings from execution
- `components/knowledge/src/ai/miniforge/knowledge/zettel.clj` - Zettel operations:
  - `create-zettel` - Factory with uid, title, content, type, optional dewey/tags/links
  - `create-link` - Link factory with required rationale
  - `add-link`, `remove-link`, `get-links` - Link management
  - `zettel->markdown` - Serialize to Markdown with YAML frontmatter
  - `markdown->zettel` - Parse Markdown back to zettel
- `components/knowledge/src/ai/miniforge/knowledge/store.clj` - Storage and queries:
  - `KnowledgeStore` protocol with put/get/delete/list/query
  - `InMemoryStore` implementation with atoms for id and uid indexes
  - `matches-query?` - Filter by tags, dewey prefixes, types, text search
  - `find-related` - Traverse links to find connected zettels
  - `search` - Full-text search with relevance scoring
  - `default-agent-manifests` - Injection configs for planner, implementer, tester, reviewer
  - `inject-knowledge` - Retrieve relevant knowledge for agent role
- `components/knowledge/src/ai/miniforge/knowledge/learning.clj` - Learning capture:
  - `capture-learning` - Create learning zettel from execution
  - `capture-inner-loop-learning` - Convenience for repair cycle insights
  - `capture-meta-loop-learning` - Convenience for observed patterns
  - `promote-learning` - Upgrade learning to rule after validation
  - `list-learnings` - Filter by confidence, agent, promotability
- `components/knowledge/src/ai/miniforge/knowledge/interface.clj` - Public API
- `components/knowledge/test/ai/miniforge/knowledge/interface_test.clj` - 15 tests, 56 assertions

### Modified Files

- `deps.edn` - Added knowledge component paths to dev/test aliases

## Design Decisions

1. **Zettelkasten Principles**: Based on Luhmann's method as documented at zettelkasten.de.
   Each zettel is atomic, links have explicit rationales, and hub notes provide navigation.

2. **Dewey Integration**: The existing Dewey classification system (000-999) categorizes
   zettels by domain. Agent manifests use Dewey prefixes to select relevant knowledge.

3. **Required Link Rationale**: Links must explain *why* the connection exists (minimum
   10 characters). This forces thoughtful linking rather than arbitrary associations.

4. **Agent Injection Manifests**: Each agent role has a manifest specifying:
   - Dewey prefixes to include
   - Tags to prioritize
   - Zettel types relevant to the role
   - Maximum zettels to inject

5. **Learning Pipeline**: Learnings start with confidence scores. High-confidence learnings
   can be promoted to rules after human review.

6. **Markdown Serialization**: Zettels serialize to Markdown with YAML frontmatter for
   human readability and version control.

## Agent Manifests

| Role | Dewey Prefixes | Types | Tags |
|------|---------------|-------|------|
| Planner | 000, 700 | rule, decision, hub | architecture, planning, workflow |
| Implementer | 200, 400 | rule, learning, example | coding, clojure, testing |
| Tester | 400 | rule, example, learning | testing, coverage, assertions |
| Reviewer | 000, 200, 400 | rule, learning | code-review, quality |

## Testing

```bash
clojure -M:dev:test -e "(require '[clojure.test :as t]) (require '[ai.miniforge.knowledge.interface-test]) (t/run-tests 'ai.miniforge.knowledge.interface-test)"

# Testing ai.miniforge.knowledge.interface-test
# Ran 15 tests containing 56 assertions.
# 0 failures, 0 errors.
```

## Dependencies

- `ai.miniforge/schema` - Type definitions (local)
- `ai.miniforge/logging` - Structured logging (local)
- `metosin/malli` - Schema validation
- `clj-commons/clj-yaml` - YAML parsing for frontmatter

## Future Work

- Persistent storage backend (Datalevin or file-based)
- Graph visualization of zettel networks
- Automatic link suggestions based on content similarity
- Integration with meta-loop for automated learning capture
