<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Knowledge Component Design

## Vision

A Zettelkasten-style knowledge system for AI agents that combines:

- **Dewey Classification** (existing): Categorical organization for rules
- **Atomic Notes (Zettels)**: Single-idea knowledge units
- **Explicit Links**: Connections with stated rationale
- **Hub/Structure Notes**: Navigation and synthesis
- **Learning Capture**: Recording insights from inner/meta loops

Each agent has access to the same knowledge base (not siloed), enabling cross-agent learning and rule sharing.

## Core Concepts

### Zettel (Atomic Note)

A single unit of knowledge with:

```clojure
{:zettel/id        uuid?
 :zettel/uid       string?          ; Human-readable: "210-clojure-ns-structure"
 :zettel/title     string?
 :zettel/content   string?          ; Markdown body
 :zettel/type      keyword?         ; :rule, :learning, :concept, :example, :hub
 :zettel/dewey     string?          ; "210" - optional Dewey classification
 :zettel/tags      [keyword?]       ; Flat tags for filtering
 :zettel/links     [Link?]          ; Outgoing links with rationale
 :zettel/backlinks [uuid?]          ; Incoming link IDs (computed)
 :zettel/source    Source?          ; Origin (manual, learning, import)
 :zettel/created   inst?
 :zettel/modified  inst?
 :zettel/author    string?}         ; "user", "agent:planner", etc.
```

### Link (Explicit Connection)

Links must state WHY they connect:

```clojure
{:link/target-id   uuid?
 :link/type        keyword?         ; :supports, :contradicts, :extends, :applies-to, :example-of
 :link/rationale   string?          ; "This rule applies when..." - REQUIRED
 :link/strength    double?          ; 0.0-1.0 relevance weight
 :link/bidirectional? boolean?}     ; Create backlink automatically
```

### Hub (Structure Note)

A special Zettel that organizes others:

```clojure
{:zettel/type :hub
 :zettel/content "## Overview of Clojure Conventions

 This hub collects all rules and learnings about Clojure development in miniforge.

 ### Core Rules
 - [[210-clojure]] - Main language conventions
 - [[210-clojure-ns-structure]] - Namespace organization

 ### Learnings from Practice
 - [[L-2026-01-19-protocol-naming]] - Why we chose protocol names

 ### Examples
 - [[E-agent-memory-pattern]] - Memory pattern from agent component"}
```

### Source (Provenance)

Track where knowledge came from:

```clojure
{:source/type      keyword?         ; :manual, :inner-loop, :meta-loop, :import
 :source/agent     keyword?         ; :planner, :implementer, etc.
 :source/task-id   uuid?            ; Task that generated this
 :source/context   string?          ; Additional context
 :source/confidence double?}        ; 0.0-1.0 how certain
```

## Zettel Types

| Type | Description | Created By |
|------|-------------|------------|
| `:rule` | Constraints/conventions (existing .mdc files) | Manual |
| `:concept` | Definitions, explanations | Manual/Agent |
| `:learning` | Insights from execution | Inner/Meta loop |
| `:example` | Concrete code/pattern examples | Agent |
| `:hub` | Structure note organizing others | Manual/Agent |
| `:question` | Open questions to resolve | Agent |
| `:decision` | ADR-style decision records | Manual/Agent |

## Query System

### Contextual Retrieval

Agents query knowledge based on context:

```clojure
(defn query-knowledge
  "Retrieve relevant zettels for agent context."
  [store {:keys [agent-role task-type tags dewey-range include-types
                 exclude-types min-strength traverse-links?]}]
  ;; Returns scored list of relevant zettels
  )
```

### Example Queries

```clojure
;; Planner needs architecture rules
(query-knowledge store
  {:agent-role :planner
   :dewey-range ["000" "099"]  ; Foundations
   :include-types [:rule :decision]})

;; Implementer needs Clojure rules + learnings
(query-knowledge store
  {:agent-role :implementer
   :tags [:clojure]
   :include-types [:rule :learning :example]
   :traverse-links? true})

;; Find all knowledge related to a specific zettel
(query-knowledge store
  {:related-to "210-clojure"
   :traverse-links? true
   :max-hops 2})
```

## Agent Injection

Each agent role has a manifest of relevant knowledge:

```clojure
{:planner
 {:dewey-prefixes ["000" "700"]           ; Foundations, Workflows
  :tags [:architecture :planning]
  :types [:rule :decision :hub]
  :hubs ["hub-architecture" "hub-workflow"]}

 :implementer
 {:dewey-prefixes ["200" "400"]           ; Languages, Testing
  :tags [:coding :testing]
  :types [:rule :learning :example]
  :hubs ["hub-clojure" "hub-testing"]}

 :tester
 {:dewey-prefixes ["400"]                 ; Testing
  :tags [:testing :coverage :assertions]
  :types [:rule :example]
  :hubs ["hub-testing"]}}
```

## Learning Capture

### Inner Loop Learning

When the inner loop discovers something (e.g., repair pattern works):

```clojure
(capture-learning store
  {:type :inner-loop
   :agent :implementer
   :task-id task-id
   :title "Protocol method collision with JVM"
   :content "When using `clear` as a protocol method, it collides with
            java.lang.Object methods. Use descriptive names like `clear-messages`."
   :tags [:clojure :protocol :gotcha]
   :links [{:target-id (find-zettel "210-clojure")
            :type :extends
            :rationale "Adds detail to Clojure conventions about protocols"}]
   :confidence 0.8})
```

### Meta Loop Learning

Patterns observed across multiple executions:

```clojure
(capture-learning store
  {:type :meta-loop
   :title "Test file naming validation is fragile"
   :content "Regex pattern for test file detection catches false positives
            when source files contain '_test' in the name."
   :tags [:testing :validation :pattern]
   :related-tasks [task-1 task-2 task-3]
   :confidence 0.9})
```

## Storage

### File-Based (Primary)

Zettels stored as Markdown with YAML frontmatter:

```markdown
---
id: 550e8400-e29b-41d4-a716-446655440000
uid: 210-clojure-ns-structure
type: rule
dewey: "210"
tags: [clojure, namespace, conventions]
links:
  - target: 210-clojure
    type: extends
    rationale: "Details namespace structure within main Clojure rule"
created: 2026-01-19T10:30:00Z
author: user
---

# Clojure Namespace Structure

Namespaces in this project follow Polylith conventions...

## Links

- Extends: [[210-clojure]] - main Clojure conventions
- Example: [[E-agent-interface]] - how agent interface is structured
```

### Index (Datalevin)

Fast queries via indexed data:

```clojure
;; Schema
{:zettel/id        {:db/valueType :db.type/uuid :db/unique :db.unique/identity}
 :zettel/uid       {:db/valueType :db.type/string :db/unique :db.unique/identity}
 :zettel/type      {:db/valueType :db.type/keyword :db/index true}
 :zettel/dewey     {:db/valueType :db.type/string :db/index true}
 :zettel/tags      {:db/valueType :db.type/keyword :db/cardinality :db.cardinality/many :db/index true}
 :zettel/links     {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
 :zettel/backlinks {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}}
```

## Integration with Existing System

### Migration Path

1. Existing `.cursor/rules/*.mdc` files become `:rule` type zettels
2. Dewey classification preserved in `:zettel/dewey`
3. `agents.md` becomes hub note `hub-agent-knowledge`
4. `000-index.mdc` becomes hub note `hub-rules-index`

### Component Dependencies

```text
knowledge (new)
├── schema      ; Malli schemas for zettels
├── artifact    ; Datalevin for index
└── logging     ; Structured logging
```

## API Surface

```clojure
;; CRUD
(create-zettel store zettel)
(get-zettel store id-or-uid)
(update-zettel store id changes)
(delete-zettel store id)

;; Links
(add-link store from-id to-id link-attrs)
(remove-link store from-id to-id)
(get-links store id direction)  ; :outgoing, :incoming, :both

;; Query
(query-knowledge store query-map)
(find-related store id {:max-hops 2})
(search store text-query)

;; Learning
(capture-learning store learning-map)
(promote-learning store learning-id)  ; Learning → Rule

;; Agent integration
(inject-knowledge store agent-role task-context)
(get-agent-manifest agent-role)

;; Sync
(sync-from-files store dir-path)
(export-to-files store dir-path)
```

## Example Flow

1. **Agent starts task**: Planner receives "implement user auth"
2. **Knowledge injection**: System queries relevant zettels:
   - Hub: `hub-architecture` (entry point)
   - Rules: `001-stratified-design`, `210-clojure`
   - Learnings: Recent auth-related insights
3. **Agent executes**: Using injected knowledge as context
4. **Learning capture**: If repair discovers new pattern, capture it
5. **Knowledge grows**: New learning linked to existing rules

## Future Extensions

- **Similarity search**: Embeddings for semantic retrieval
- **Forgetting**: Decay confidence on unused/outdated knowledge
- **Contradiction detection**: Flag conflicting rules
- **Knowledge graphs**: Visualize connections
- **Cross-project sharing**: Portable knowledge across repos
