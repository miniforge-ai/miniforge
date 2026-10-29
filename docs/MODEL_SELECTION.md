<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Model Selector

The model selector is a library API that picks a model from the model catalog for a classified task. No workflow
calls it: a workflow run gives each role the model its configuration names, as
[Configuration Management](configuration.md) describes, and no environment variable or `config.edn` key reaches the
selector. The only caller in the codebase is `agent/create-agent`, and no workflow, phase or CLI command calls that.

## How it works

1. `agent/classify-task` reads a task map (`:phase`, `:agent-type`, `:description`, `:title`, `:context-tokens`,
   `:privacy-required`, `:cost-limit`) and returns a classification such as
   `{:type :thinking-heavy :confidence 0.9 :reason "..."}`.
2. `llm/select-model` takes that classification, a config map (`:strategy`) and constraints (`:context-size`,
   `:cost-limit`, `:require-local`). It walks the recommendation tiers for the task type and returns the first model
   that is available and meets the constraints.
3. `agent/create-agent` runs both steps unless its options carry `:model` or `:model-selection-enabled false`, and
   logs the choice.

The tiers per task type are `:task-type-recommendations` in
[model-catalog.edn](../components/llm/resources/llm/model-catalog.edn), which also holds every model's profile:

| Task type | Tiers |
|-----------|-------|
| `:thinking-heavy` | tier-1, tier-2, tier-3-local |
| `:execution-focused` | tier-1, tier-2, tier-3-local |
| `:simple-validation` | tier-1, tier-2, tier-3-local |
| `:large-context` | tier-1, tier-2 |
| `:privacy-sensitive` | tier-1-local, tier-2-local |
| `:cost-optimized` | tier-1-free, tier-2-cheap, tier-3-moderate |

Strategies:

1. `:automatic` (default) reads tier-1, tier-2 and tier-3-local, or only the `-local` tiers when `:require-local` is
   set. If no model meets the constraints it takes the first available one.
2. `:cost-optimized` reads tier-1-free, tier-2-cheap, tier-3-moderate, then tier-1.
3. `:speed` reads tier-1 and tier-2, fastest first.

Nothing is selected when no model in the tiers the strategy reads qualifies (`:automatic` first retries without the
constraints, taking any available model), or when the strategy reads none of the task type's tiers: `:automatic` reads
none of the `:privacy-sensitive` or `:cost-optimized` tiers unless
`:require-local` is set. `select-model` is meant to return Claude Sonnet 4.6 with `:fallback-used true` then, but it
throws instead: the warning it logs on that path passes a keyword where `log/warn` expects a logger
(IllegalArgumentException, "No implementation of method: :log*"). `create-agent` catches the exception and keeps the
role's configured model.

## Availability: provider API keys

A cloud model counts as available only when its provider's API-key variable is set and not blank. These variables
are not miniforge settings. The selector only checks that they are set; it neither calls a model nor creates a
client.

| Provider | Variable |
|----------|----------|
| `:anthropic` | `ANTHROPIC_API_KEY` |
| `:openai` | `OPENAI_API_KEY` |
| `:google` | `GOOGLE_API_KEY` |
| `:groq` | `GROQ_API_KEY` |

The map is `:provider-env-vars` in `components/llm/resources/llm/model-selector.edn`. Providers not in it, such as
the local models' providers, are always available. The `:gemini-api` backend reads `GEMINI_API_KEY` for its own
calls, not `GOOGLE_API_KEY`.

## Options

Options are arguments, not configuration. Earlier versions of this page listed `MINIFORGE_MODEL_SELECTION_ENABLED`,
`MINIFORGE_MODEL_SELECTION_STRATEGY`, `MINIFORGE_MODEL_SELECTION_COST_LIMIT`,
`MINIFORGE_MODEL_SELECTION_REQUIRE_LOCAL` and a `:model-selection` config block; nothing read them, and miniforge no
longer accepts the variables.

```clojure
;; Strategy: :automatic (default), :cost-optimized or :speed
(llm/select-model classification {:strategy :cost-optimized})

;; Constraints
(llm/select-model classification {} {:context-size 500000 :cost-limit 0.05 :require-local true})

;; create-agent takes :model-selection-enabled, :model-selection-strategy,
;; :privacy-required and :cost-limit; an explicit :model skips selection
(agent/create-agent :implementer {:model "claude-opus-4-6"})
```

Neither constraint is a hard limit:

1. `:require-local` (`:privacy-required` on `create-agent`) does not keep data on the machine. Only the `:automatic`
   strategy reads it. When the task type lists no local model (the `:large-context` and `:cost-optimized` types list
   none), nothing is selected, and `create-agent` keeps the role's configured model, a cloud model unless the config
   names a local one. The `:cost-optimized` and `:speed` strategies ignore it. Do not use it to protect sensitive
   code.
2. `:cost-limit` filters models by price tier: a model's `:cost` level (`:economical`, `:moderate`, `:expensive`) is
   compared with fixed thresholds. It does not cap what a task spends. When no model passes, `:automatic` takes the
   first available model in the tiers it reads.

## API

```clojure
(require '[ai.miniforge.agent.interface :as agent]
         '[ai.miniforge.llm.interface :as llm])

(def classification
  (agent/classify-task {:phase :plan
                        :agent-type :planner-agent
                        :description "Design distributed architecture"}))

(def selection (llm/select-model classification))
;; With ANTHROPIC_API_KEY set:
;; => {:model :opus-4.7 :model-id "claude-opus-4-7" :provider :anthropic
;;     :backend :claude :task-type :thinking-heavy :strategy :automatic
;;     :rationale "..." :fallback-used false ...}

(llm/explain-selection selection)   ; text for a person to read

;; Catalog queries
(llm/get-model :sonnet-4.6)
(llm/get-models-by-capability :reasoning :exceptional)
(llm/get-models-by-use-case :code-implementation)
(llm/recommend-models-for-task-type :large-context)
```

`explain-selection` ends with a line naming `:spec/model-override`; nothing reads that key.

## Troubleshooting

`select-model` throwing IllegalArgumentException ("No implementation of method: :log*") means nothing was selected
(see How it works). Check the provider API-key variables under Availability, and whether the strategy reads the task
type's tiers at all.

## Further Reading

- [Model catalog](../components/llm/resources/llm/model-catalog.edn) - Model profiles and per-task-type tiers
- [Task Classifier](../components/agent/src/ai/miniforge/agent/task_classifier.clj) - Classification logic
- [Model Selector](../components/llm/src/ai/miniforge/llm/model_selector.clj) - Selection strategies
