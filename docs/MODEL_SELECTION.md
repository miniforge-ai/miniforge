<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Model Selector

**Experimental.** The model selector is a library API that picks a model from the model catalog for a classified
task. No workflow, phase or CLI command calls it: a run gives each role the model its configuration names, as
[Configuration Management](configuration.md) describes. No environment variable or `config.edn` key reaches the
selector; earlier versions of this page listed `MINIFORGE_MODEL_SELECTION_*` variables and a `:model-selection`
config block, which nothing read.

## API

```clojure
(require '[ai.miniforge.agent.interface :as agent]
         '[ai.miniforge.llm.interface :as llm])

;; Task map -> classification {:type :thinking-heavy :confidence 0.9 :reason "..."}
(agent/classify-task {:phase :plan :description "Design distributed architecture"})

;; Classification -> selection map (here with ANTHROPIC_API_KEY set)
(llm/select-model classification)
;; => {:model :opus-4.7 :model-id "claude-opus-4-7" :provider :anthropic
;;     :backend :claude :task-type :thinking-heavy :confidence 0.9
;;     :strategy :automatic :rationale "..." :fallback-used false}

;; Optional config and constraints
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

The models, their profiles and the recommendation tiers per task type are in
[model-catalog.edn](../components/llm/resources/llm/model-catalog.edn). How a strategy walks those tiers is in
[model_selector.clj](../components/llm/src/ai/miniforge/llm/model_selector.clj); this page does not restate it.

## Known limitations

1. When no strategy picks a model, `select-model` throws IllegalArgumentException ("No implementation of method:
   :log*") instead of returning its Claude Sonnet 4.6 fallback. `create-agent` catches it and keeps the role's
   configured model. Fixed in #2198.
2. `:require-local` is not a hard constraint: a call can return a cloud model. #2198 makes `select-model` return
   an anomaly instead.
3. `:automatic` can return a model that fails `:context-size` or `:cost-limit` while reporting
   `:fallback-used false`. With `:require-local` it reads only `-local` tier keys, which `:large-context` and
   `:cost-optimized` do not define. Fixed in #2203. Until then, check the selected model's context window.
4. `:cost-limit` filters models by price tier; it does not cap what a task spends.
5. A cloud model counts as available only when its provider's API-key variable is set (`ANTHROPIC_API_KEY`,
   `OPENAI_API_KEY`, `GOOGLE_API_KEY`, `GROQ_API_KEY`; see `:provider-env-vars` in
   `components/llm/resources/llm/model-selector.edn`). The selector only checks that the variable is set.
6. `explain-selection` ends with a line naming `:spec/model-override`; nothing reads that key.
