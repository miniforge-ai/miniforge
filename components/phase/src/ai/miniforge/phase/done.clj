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
  (:require [ai.miniforge.phase.registry :as registry]))

;------------------------------------------------------------------------------ Layer 0
;; Defaults

(def default-config
  "Terminal phase has no agent, no gates, and a minimal budget."
  {:agent nil
   :gates []
   :budget {:tokens 0 :iterations 1 :time-seconds 1}})

;; Register defaults on load
(registry/register-phase-defaults! :done default-config)

;------------------------------------------------------------------------------ Layer 1
;; Interceptor

(defn- enter-done
  "Mark both the phase and the enclosing execution as completed."
  [ctx]
  (-> ctx
      (assoc-in [:phase :name] :done)
      (assoc-in [:phase :status] :completed)
      (assoc-in [:execution :status] :completed)))

;------------------------------------------------------------------------------ Layer 2
;; Registry method

(defmethod registry/get-phase-interceptor :done
  [config]
  (let [merged (registry/merge-with-defaults config)]
    {:name ::done
     :config merged
     :enter enter-done
     :leave identity
     :error (fn [ctx _ex] ctx)}))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (registry/get-phase-interceptor {:phase :done})
  (registry/phase-defaults :done)
  (registry/list-phases)
  :leave-this-here)
