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

(ns ai.miniforge.workflow.chain
  "Workflow chaining — execute a sequence of workflows where each step's
   output feeds into the next step's input via configurable bindings.

   Schemas (as documentation):
   - ChainStep: {:step/id keyword
                  :step/workflow-id keyword
                  :step/input-bindings {keyword path-expr}}
   - ChainDef:  {:chain/id keyword
                  :chain/steps [ChainStep...]
                  :chain/version string
                  :chain/description string}

   Path expressions for input bindings:
   - string literal          — passed through as-is
   - :chain/input.KEY        — reads KEY from the chain's initial input
   - [:prev/phase-results ...] — navigates into previous step's :execution/output
   - [:prev/artifacts ...]     — navigates into previous step's artifacts
   - [:prev/last-phase-result ...] — navigates into previous step's last phase result
   - keyword                 — reads from chain input"
  (:require
   [ai.miniforge.workflow.runner :as runner]))

;------------------------------------------------------------------------------ Layer 0: Binding resolution

(defn- resolve-binding
  "Resolve a single input binding against previous output and chain input.
   Path expressions:
   - :chain/input.KEY — reads KEY from the chain's initial input
   - [:prev/KEY1 :KEY2 ...] — reads from previous step's :execution/output
   - string literal — passed through as-is"
  [binding prev-output chain-input]
  (cond
    ;; String literal — pass through
    (string? binding) binding

    ;; Keyword starting with :chain/input. — read from chain input
    (and (keyword? binding)
         (.startsWith (name binding) "chain/input."))
    (let [input-key (keyword (subs (name binding) (count "chain/input.")))]
      (get chain-input input-key))

    ;; Vector path — navigate into prev-output
    (vector? binding)
    (let [[root & path] binding
          source (case root
                   :prev/phase-results (:phase-results prev-output)
                   :prev/artifacts (:artifacts prev-output)
                   :prev/last-phase-result (:last-phase-result prev-output)
                   prev-output)]
      (if (seq path)
        (get-in source (vec path))
        source))

    ;; Keyword — try chain-input
    (keyword? binding) (get chain-input binding)))

(defn- resolve-bindings
  "Resolve all input bindings for a step."
  [input-bindings prev-output chain-input]
  (reduce-kv
    (fn [acc k binding]
      (assoc acc k (resolve-binding binding prev-output chain-input)))
    {}
    input-bindings))

;------------------------------------------------------------------------------ Layer 1: Chain execution

(defn run-chain
  "Execute a chain of workflows sequentially.

   Arguments:
   - chain-def: Chain definition map with :chain/steps
   - chain-input: Initial input map for the chain
   - opts: Execution options passed to each run-pipeline call
     Must include everything run-pipeline needs (e.g. :llm-backend)

   Returns:
   {:chain/id keyword
    :chain/status :completed | :failed
    :chain/step-results [step-result...]
    :chain/duration-ms long}"
  [chain-def chain-input opts]
  (let [start-time (System/currentTimeMillis)
        steps (:chain/steps chain-def)
        load-workflow (requiring-resolve 'ai.miniforge.workflow.interface/load-workflow)]
    (loop [remaining steps
           prev-output nil
           step-results []
           idx 0]
      (if (empty? remaining)
        ;; All steps completed successfully
        {:chain/id (:chain/id chain-def)
         :chain/status :completed
         :chain/step-results step-results
         :chain/step-count (count steps)
         :chain/duration-ms (- (System/currentTimeMillis) start-time)}

        ;; Execute next step
        (let [step (first remaining)
              input-bindings (:step/input-bindings step)
              resolved-input (resolve-bindings input-bindings prev-output chain-input)
              workflow-result (load-workflow (:step/workflow-id step) :latest {})
              workflow (:workflow workflow-result)
              result (runner/run-pipeline workflow resolved-input opts)
              output (:execution/output result)
              step-result {:step/id (:step/id step)
                           :step/workflow-id (:step/workflow-id step)
                           :step/status (:execution/status result)
                           :step/output output
                           :step/chain-id (:chain/id chain-def)
                           :step/chain-index idx}
              updated-results (conj step-results step-result)]
          (if (= :failed (:execution/status result))
            ;; Step failed — stop the chain immediately
            {:chain/id (:chain/id chain-def)
             :chain/status :failed
             :chain/step-results updated-results
             :chain/step-count (count steps)
             :chain/duration-ms (- (System/currentTimeMillis) start-time)}

            ;; Step succeeded — continue with next step
            (recur (rest remaining)
                   output
                   updated-results
                   (inc idx))))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Example chain definition
  (def example-chain
    {:chain/id :plan-then-implement
     :chain/version "1.0.0"
     :chain/description "Plan a feature, then implement it"
     :chain/steps
     [{:step/id :plan
       :step/workflow-id :planning-v1
       :step/input-bindings {:task :chain/input.task}}
      {:step/id :implement
       :step/workflow-id :implementation-v1
       :step/input-bindings {:plan [:prev/last-phase-result :plan]
                             :task :chain/input.task}}]})

  ;; Run a chain
  #_(run-chain example-chain {:task "Build login page"} {:llm-backend nil})

  :leave-this-here)
