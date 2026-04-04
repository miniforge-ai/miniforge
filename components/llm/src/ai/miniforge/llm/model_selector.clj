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
   Layer 2: Selection orchestration with fallback logic"
  (:require
   [ai.miniforge.llm.model-registry :as registry]
   [ai.miniforge.logging.interface :as log]))

;------------------------------------------------------------------------------ Layer 0
;; Constraints and availability

(def default-config
  "Default configuration for model selection."
  {:enabled true
   :strategy :automatic ; :automatic | :fixed | :cost-optimized
   :cost-limit-per-task 0.10
   :prefer-speed false
   :allow-downgrade true
   :require-local false})

(defn model-available?
  "Check if a model is available in the current environment.
   TODO: Integrate with backend health checking."
  [_model-key]
  ;; For now, assume all models are available
  ;; In production, this would check:
  ;; - Backend health status
  ;; - API keys configured
  ;; - CLI tools installed
  ;; - Local models downloaded
  true)

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
        :economical (>= cost-limit 0.01)
        :moderate (>= cost-limit 0.05)
        :expensive (>= cost-limit 0.10)
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
                              (meets-cost-constraint? model-key (or cost-limit 0.10))))
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
                          (meets-cost-constraint? model-key (or cost-limit 0.05))))
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
                          (meets-cost-constraint? model-key (or cost-limit 0.10))))
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
   (let [merged-config (merge default-config config)
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
                             :confidence 0.9
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
