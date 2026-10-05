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
   [ai.miniforge.event-stream.interface :as events]
   [ai.miniforge.workflow.chain-bindings :as bindings]
   [ai.miniforge.phase.interface :as phase]
   [ai.miniforge.workflow.loader :as loader]
   [ai.miniforge.workflow.runner :as runner]))

;------------------------------------------------------------------------------ Layer 0

;; Binding resolution
(def ^{:stratum 0} resolve-binding bindings/resolve-binding)

(def ^{:stratum 0} resolve-bindings bindings/resolve-bindings)

;; Event emission
(defn ^{:stratum 0} emit!
  "Emit a chain event if event-stream is present in opts."
  [opts constructor & args]
  (when-let [stream (:event-stream opts)]
    (events/publish! stream (apply constructor stream args))))

;------------------------------------------------------------------------------ Layer 1

;; Chain execution
(defn ^{:stratum 1} run-chain
  "Execute a chain of workflows sequentially.

   Arguments:
   - chain-def: Chain definition map with :chain/steps
   - chain-input: Initial input map for the chain
   - opts: Execution options passed to each run-pipeline call
     Must include everything run-pipeline needs (e.g. :llm-backend)
     Optional :event-stream for chain lifecycle events.

   Returns:
   {:chain/id keyword
    :chain/status :completed | :failed
    :chain/step-results [step-result...]
    :chain/step-count int
    :chain/duration-ms long}"
  [chain-def chain-input opts]
  (let [start-time (System/currentTimeMillis)
        chain-id (:chain/id chain-def)
        steps (:chain/steps chain-def)
        load-workflow loader/load-workflow
        ;; One correlation id per chain invocation, threaded onto every
        ;; step's run-pipeline opts so all steps and their lifecycle
        ;; events share it. Mint-if-absent: a caller may supply one to
        ;; correlate the whole ETL invocation from outside.
        correlation-id (or (:workflow-run/correlation-id opts) (random-uuid))
        step-opts (assoc opts :workflow-run/correlation-id correlation-id)]
    (emit! opts events/chain-started
           chain-id (count steps))
    (loop [remaining steps
           prev-output nil
           step-results []
           idx 0]
      (if (empty? remaining)
        ;; All steps completed successfully
        (let [duration-ms (- (System/currentTimeMillis) start-time)]
          (emit! opts events/chain-completed
                 chain-id duration-ms (count steps))
          {:chain/id chain-id
           :chain/status :completed
           :chain/step-results step-results
           :chain/step-count (count steps)
           :chain/duration-ms duration-ms})

        ;; Execute next step
        (let [step (first remaining)
              step-id (:step/id step)
              workflow-id (:step/workflow-id step)
              _ (emit! opts events/chain-step-started
                       chain-id step-id idx workflow-id)
              input-bindings (:step/input-bindings step)
              resolved-input (resolve-bindings input-bindings prev-output chain-input)
              workflow-result (load-workflow workflow-id :latest {:skip-validation? true})
              workflow (:workflow workflow-result)
              result (runner/run-pipeline workflow resolved-input step-opts)
              output (:execution/output result)
              step-result {:step/id step-id
                           :step/workflow-id workflow-id
                           :step/execution-id (:execution/id result)
                           :step/status (:execution/status result)
                           :step/output output
                           :step/chain-id chain-id
                           :step/chain-index idx}
              updated-results (conj step-results step-result)]
          (if (phase/failed? result)
            ;; Step failed — emit failure events and stop
            (let [error (get result :execution/error "Step execution failed")]
              (emit! opts events/chain-step-failed
                     chain-id step-id idx error)
              (emit! opts events/chain-failed
                     chain-id step-id error)
              {:chain/id chain-id
               :chain/status :failed
               :chain/step-results updated-results
               :chain/step-count (count steps)
               :chain/duration-ms (- (System/currentTimeMillis) start-time)})

            ;; Step succeeded — emit completion and continue
            (do
              (emit! opts events/chain-step-completed
                     chain-id step-id idx)
              (recur (rest remaining)
                     output
                     updated-results
                     (inc idx)))))))))

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
