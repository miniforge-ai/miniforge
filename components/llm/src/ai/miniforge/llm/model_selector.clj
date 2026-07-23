;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns ai.miniforge.llm.model-selector
  "Intelligent model selection based on task classification.
   Layer 0: Selection constraints and availability checking
   Layer 1: Model selection strategies
   Layer 2: Selection orchestration with fallback logic

   Operational configuration (default selection policy + the
   provider→env-var map) lives in `resources/llm/model-selector.edn` per
   standards rule 007 (config-as-data). The constants below are
   code-side FALLBACKS used only when that resource is absent at
   classpath load."
  (:require
   [ai.miniforge.llm.model-registry :as registry]
   [ai.miniforge.logging.interface :as log]
   [clojure.edn :as edn]
   [clojure.java.io :as io]))

;------------------------------------------------------------------------------ Layer 0
;; Constraints and availability

(def ^:private default-cost-limit-usd
  "Fallback per-task cost limit ($) when neither config nor caller specifies
   one. Used by `default-config-fallback` and by the selection fns'
   `(or cost-limit ...)` guards so the unspecified-budget default has a single
   name. 0.10 comfortably covers a routine Sonnet/Haiku task."
  0.10)

(def default-config-fallback
  "Hard-coded fallback when `llm/model-selector.edn :default-config` is
   absent. The active value comes from that EDN file per rule 007."
  {:enabled true
   :strategy :automatic ; :automatic | :fixed | :cost-optimized
   :cost-limit-per-task default-cost-limit-usd
   :prefer-speed false
   :allow-downgrade true
   :require-local false})

(def ^:private economical-cost-threshold-usd
  "Minimum per-task budget ($) at which an :economical model is
   affordable in meets-cost-constraint?."
  0.01)

(def ^:private moderate-cost-threshold-usd
  "Minimum per-task budget ($) at which a :moderate model is affordable."
  0.05)

(def ^:private expensive-cost-threshold-usd
  "Minimum per-task budget ($) at which an :expensive model is
   affordable. Equal to the default budget — the default is set to just afford
   an expensive model when the caller is silent."
  default-cost-limit-usd)

(def ^:private cost-optimized-default-limit-usd
  "Fallback per-task budget ($) for the cost-optimized selection strategy when
   the caller gives none. Tighter than the general default so cost-optimized
   selection actually prefers cheaper tiers."
  0.05)

(def ^:private phase-classification-confidence
  "Confidence assigned to a task classification derived from the SDLC phase
   (rather than LLM-inferred). 0.9 — the phase→type mapping is deterministic
   and near-certain, but a phase can occasionally host atypical work, so not
   1.0."
  0.9)

(def ^:private fallback-provider-env-vars
  "Hard-coded fallback when `llm/model-selector.edn :provider-env-vars` is
   absent. Operators override by editing the EDN file, not this constant."
  {:anthropic "ANTHROPIC_API_KEY"
   :openai    "OPENAI_API_KEY"
   :google    "GOOGLE_API_KEY"
   :groq      "GROQ_API_KEY"})

(defn- load-model-selector-config
  []
  (if-let [resource (io/resource "llm/model-selector.edn")]
    (edn/read-string (slurp resource))
    {}))

(def ^:private model-selector-config
  (delay (load-model-selector-config)))

(def default-config
  "Active default configuration for model selection. EDN-loaded value
   from `llm/model-selector.edn :default-config` if present, else the
   code-side `default-config-fallback`. Realized via `delay` so the
   resource read happens once per process."
  (delay (or (:default-config @model-selector-config)
             default-config-fallback)))

(def ^:private provider-env-vars
  (delay (or (:provider-env-vars @model-selector-config)
             fallback-provider-env-vars)))

(def get-env-var
  "Wraps System/getenv; rebind in tests to inject env state without mutating
   the real process environment."
  #(System/getenv %))

(defn model-available?
  "Returns true when the model key is registered in the model catalog and
   its provider's API key env var is present (non-blank) in the process
   environment. Local-runner models with no entry in provider-env-vars are
   always considered available. Unregistered keys return false."
  [model-key]
  (when-let [model (registry/get-model model-key)]
    (let [env-var (get @provider-env-vars (:provider model))]
      (or (nil? env-var)
          (boolean (when-let [v (get-env-var env-var)]
                     (not (.isBlank ^String v))))))))

(defn meets-context-requirement?
  "Check if model can handle the required context size."
  [model-key required-context]
  (if-let [model (registry/get-model model-key)]
    (>= (get-in model [:capabilities :context-window]) required-context)
    false))

(defn meets-cost-constraint?
  "Check if model meets cost constraint (simplified)."
  [model-key cost-limit]
  (when-let [model (registry/get-model model-key)]
    (let [cost-level (get-in model [:capabilities :cost])]
      (case cost-level
        :free true
        :economical (>= cost-limit economical-cost-threshold-usd)
        :moderate (>= cost-limit moderate-cost-threshold-usd)
        :expensive (>= cost-limit expensive-cost-threshold-usd)
        true))))

;------------------------------------------------------------------------------ Layer 1
;; Selection strategies

(defn select-by-automatic
  "Automatic selection based on task type.
   Picks the best model for the task, considering availability."
  [task-type {:keys [context-size cost-limit require-local]}]
  (let [recommendations (registry/recommend-models-for-task-type task-type)
        all-tiers (if require-local
                    ;; For local-only, use tier-3-local or tier-1-local
                    (concat (:tier-1-local recommendations)
                            (:tier-2-local recommendations)
                            (:tier-3-local recommendations))
                    ;; Otherwise try tier-1, tier-2, then tier-3-local
                    (concat (:tier-1 recommendations)
                            (:tier-2 recommendations)
                            (:tier-3-local recommendations)))]

    ;; Find first available model meeting constraints
    (or (first (filter (fn [model-key]
                         (and (model-available? model-key)
                              (meets-context-requirement? model-key (or context-size 0))
                              (meets-cost-constraint? model-key (or cost-limit default-cost-limit-usd))))
                       all-tiers))
        ;; Fallback to first available if no match
        (first (filter model-available? all-tiers)))))

(defn select-by-cost-optimized
  "Cost-optimized selection - prefer cheapest sufficient model."
  [task-type {:keys [context-size cost-limit]}]
  (let [recommendations (registry/recommend-models-for-task-type task-type)
        ;; For cost optimization, try free first, then cheap, then moderate
        tiers (concat (:tier-1-free recommendations)
                      (:tier-2-cheap recommendations)
                      (:tier-3-moderate recommendations)
                      (:tier-1 recommendations))]

    (first (filter (fn [model-key]
                     (and (model-available? model-key)
                          (meets-context-requirement? model-key (or context-size 0))
                          (meets-cost-constraint? model-key (or cost-limit cost-optimized-default-limit-usd))))
                   tiers))))

(defn select-by-speed
  "Speed-optimized selection - prefer fastest models."
  [task-type {:keys [context-size cost-limit]}]
  (let [recommendations (registry/recommend-models-for-task-type task-type)
        all-models (concat (:tier-1 recommendations)
                           (:tier-2 recommendations))
        ;; Sort by speed capability
        sorted-by-speed (sort-by
                         (fn [model-key]
                           (let [speed (get-in (registry/get-model model-key)
                                               [:capabilities :speed])]
                             (case speed
                               :very-fast 0
                               :fast 1
                               :balanced 2
                               :moderate 3
                               :slow 4
                               5)))
                         all-models)]

    (first (filter (fn [model-key]
                     (and (model-available? model-key)
                          (meets-context-requirement? model-key (or context-size 0))
                          (meets-cost-constraint? model-key (or cost-limit default-cost-limit-usd))))
                   sorted-by-speed))))

;------------------------------------------------------------------------------ Layer 2
;; Selection orchestration

(defn build-selection-rationale
  "Build human-readable explanation of model selection."
  [model-key task-classification strategy constraints]
  (let [model (registry/get-model model-key)
        task-type (:type task-classification)
        confidence (:confidence task-classification)
        recommendations (registry/recommend-models-for-task-type task-type)]

    (str
     (format "Task: %s (confidence: %.0f%%)\n" (name task-type) (* confidence 100))
     (format "Selected Model: %s (%s)\n" (:model-id model) (name (:provider model)))
     (format "Strategy: %s\n" (name strategy))
     (when-let [rationale (:rationale recommendations)]
       (format "Rationale: %s\n" rationale))
     (when (:require-local constraints)
       "Constraint: Local inference required\n")
     (when (:context-size constraints)
       (format "Context: %d tokens\n" (:context-size constraints)))
     (format "Best For: %s" (first (:best-for model))))))

(defn select-model
  "Main entry point for intelligent model selection.

   Input:
   - task-classification: Result from task-classifier/classify-task
   - config: Optional configuration overrides
   - constraints: Optional constraints {:context-size :cost-limit :require-local}

   Output:
   {:model :sonnet-4.6
    :model-id \"claude-sonnet-4-6\"
    :provider :anthropic
    :backend :claude
    :task-type :execution-focused
    :confidence 0.9
    :strategy :automatic
    :rationale \"...\"
    :fallback-used false}"
  ([task-classification]
   (select-model task-classification {} {}))
  ([task-classification config]
   (select-model task-classification config {}))
  ([task-classification config constraints]
   (let [merged-config (merge @default-config config)
         strategy (:strategy merged-config)
         task-type (:type task-classification)
         confidence (:confidence task-classification)

         ;; Select model based on strategy
         selected (case strategy
                    :automatic (select-by-automatic task-type constraints)
                    :cost-optimized (select-by-cost-optimized task-type constraints)
                    :speed (select-by-speed task-type constraints)
                    ;; Default to automatic
                    (select-by-automatic task-type constraints))

         ;; If no model found, use a safe fallback
         model-key (or selected :sonnet-4.6)
         model (registry/get-model model-key)
         fallback-used (not selected)]

     (when fallback-used
       (log/warn ::model-selection-fallback
                 "No model met constraints, using fallback"
                 {:task-type task-type
                  :fallback model-key}))

     {:model model-key
      :model-id (:model-id model)
      :provider (:provider model)
      :backend (:backend model)
      :task-type task-type
      :confidence confidence
      :strategy strategy
      :rationale (build-selection-rationale model-key task-classification strategy constraints)
      :fallback-used fallback-used})))

(defn select-model-for-phase
  "Select model specifically for a workflow phase.
   Convenience function that creates task classification from phase."
  [phase & {:keys [config constraints]}]
  (let [task-classification {:type (case phase
                                      (:plan :design :architecture) :thinking-heavy
                                      (:validate :format :lint) :simple-validation
                                      :execution-focused)
                             :confidence phase-classification-confidence
                             :reason (format "Phase-based classification for '%s'" phase)}]
    (select-model task-classification config constraints)))

(defn explain-selection
  "Generate user-facing explanation of model selection."
  [selection]
  (str "Model Auto-Selected: " (name (:model selection)) "\n"
       "\n"
       (:rationale selection)
       "\n"
       (when (:fallback-used selection)
         "\nNote: Primary model unavailable, using fallback")
       "\nOverride: Set :spec/model-override to force a specific model"))
