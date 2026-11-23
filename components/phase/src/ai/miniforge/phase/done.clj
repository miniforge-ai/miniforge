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
(ns ai.miniforge.phase.done
  "Terminal `:done` phase interceptor.

   `:done` is an FSM/phase-core concept — every workflow EDN may declare
   `{:phase :done}` as its terminal step, and the workflow FSM in
   `ai.miniforge.workflow.fsm` treats `:done` as the canonical
   already-done sentinel (see `done-index` in `build-phase-state`).

   Lives in the `phase` core component so any workflow runtime that
   loads the phase registry gets `:done` for free, without depending on
   `phase-software-factory`."
  (:require [ai.miniforge.phase.interface.gate :as gate]
            [ai.miniforge.phase.registry :as registry]))

;------------------------------------------------------------------------------ Layer 0

;; Defaults
(def ^{:stratum 0} default-config
  "Terminal phase has no agent, no gates, and a minimal budget."
  {:agent nil
   :phase/expected-agent :agent/none
   :gates []
   :budget {:tokens 0 :iterations 1 :time-seconds 1}})

;; Interceptor
(defn- ^{:stratum 0} preserve-context [ctx _error] ctx)

(defn- ^{:stratum 0} leave-done
  "A terminal phase its gates failed does not complete the run."
  [ctx]
  (cond-> ctx
    (gate/gate-failed? (:phase ctx))
    (-> (assoc-in [:phase :status] :failed)
        (assoc-in [:execution :status] :failed))))

(defn- ^{:stratum 0} completed-phase [config start end]
  (let [duration (max 0 (- end start))
        metrics {:tokens 0 :cost-usd 0.0 :duration-ms duration}]
    (merge (select-keys config [:agent :gates :budget])
           {:name :done :status :completed
            :started-at start :ended-at end :duration-ms duration
            :metrics metrics
            :result {:status :success :artifacts [] :metrics metrics}})))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} enter-done
  "Complete the terminal phase with its own facts, without producing artifacts."
  [config ctx]
  (let [start (System/currentTimeMillis)
        completed (assoc-in ctx [:execution :status] :completed)
        end (System/currentTimeMillis)]
    (assoc completed :phase (completed-phase config start end))))

;------------------------------------------------------------------------------ Layer 2

;; Registry method
(defmethod ^{:stratum 2} registry/get-phase-interceptor :done
  [config]
  (let [merged (registry/merge-with-defaults config)]
    {:name ::done
     :config merged
     :enter (partial enter-done merged)
     :leave leave-done
     :error preserve-context}))

;; Register defaults on load
(registry/register-phase-defaults! :done default-config)

;------------------------------------------------------------------------------ Rich Comment
(comment
  (registry/get-phase-interceptor {:phase :done})
  (registry/phase-defaults :done)
  (registry/list-phases)
  :leave-this-here)
