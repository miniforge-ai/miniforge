<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Model Selector

**Experimental.** The model selector is a library API that picks a model from the model catalog for a classified
task. No workflow, phase or CLI command calls it: a run gives each role the model its configuration names, as
[Configuration Management](configuration.md) describes. No miniforge setting configures it: the
`MINIFORGE_MODEL_SELECTION_*` variables and the `:model-selection` config block that earlier versions of this page
listed were never read. It does read provider API-key variables (see Known limitations).

## API

```clojure
(require '[ai.miniforge.agent.interface :as agent]
         '[ai.miniforge.llm.interface :as llm])

;; Task map -> classification {:type :thinking-heavy :confidence 0.9 :reason "..."}
(def classification
  (agent/classify-task {:phase :plan :description "Design distributed architecture"}))

;; Classification -> selection map (here with ANTHROPIC_API_KEY set)
(def selection (llm/select-model classification))
;; => {:model :opus-4.7 :model-id "claude-opus-4-7" :provider :anthropic
;;     :backend :claude :task-type :thinking-heavy :confidence 0.9
;;     :strategy :automatic :rationale "..." :fallback-used false}

;; Optional config and constraints. A result that fails one is a :not-found
;; anomaly naming the unmet constraints instead of a selection.
(llm/select-model classification
                  {:strategy :cost-optimized}     ; :automatic (default), :cost-optimized, :speed
                  {:context-size 500000           ; tokens
                   :cost-limit 0.05               ; see Known limitations
                   :require-local true})

(llm/explain-selection selection)                 ; text for a person to read

;; Catalog queries
(llm/get-model :sonnet-4.6)
(llm/recommend-models-for-task-type :large-context)

;; Classifies and selects unless opts carry :model or :model-selection-enabled false
(agent/create-agent :implementer {:privacy-required true :cost-limit 0.05})
```

When the chosen strategy picks no candidate, `select-model` returns Claude Sonnet 4.6 with `:fallback-used true`;
another strategy might have found one (`:cost-optimized` skips the local tiers of `:privacy-sensitive`). The result,
the fallback included, is checked against each constraint the caller set (`:require-local`, `:context-size`,
`:cost-limit`); a model that fails one becomes a `:not-found` anomaly naming the unmet constraints.

**Selection routes nothing.** `create-agent` stores the selected model id in the agent's config; `invoke` sends the
task to the `:llm-backend` (an `agent/LLMBackend`) in its context and never reads the selection, so
`:privacy-required` keeps nothing on the machine. A client from `llm/create-client` is not an `agent/LLMBackend` and
needs an adapter. When `select-model` returns an anomaly, `create-agent` still returns an agent with the role's
default model and keeps the anomaly at `[:state :model-selection]`; check it.

The models, their profiles and the recommendation tiers per task type are in
[model-catalog.edn](../components/llm/resources/llm/model-catalog.edn). How a strategy walks those tiers is in
[model_selector.clj](../components/llm/src/ai/miniforge/llm/model_selector.clj); this page does not restate it.

## Known limitations

1. `:cost-limit` filters models by price tier; it does not cap what a task spends.
2. A cloud model counts as available only when its provider's API-key variable is set (`ANTHROPIC_API_KEY`,
   `OPENAI_API_KEY`, `GOOGLE_API_KEY`, `GROQ_API_KEY`; see `:provider-env-vars` in
   `components/llm/resources/llm/model-selector.edn`). The selector only checks that the variable is set, and not
   for the Sonnet fallback: a fallback result says nothing about provider availability.
3. `explain-selection` ends with a line naming `:spec/model-override`; nothing reads that key.
