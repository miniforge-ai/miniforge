;; Copyright 2025 miniforge.ai
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

(ns ai.miniforge.phase.plan
  "Planning phase interceptor.

   Creates implementation plans from specifications.
   Agent: :planner
   Default gates: [:plan-complete]"
  (:require [ai.miniforge.phase.registry :as registry]))

;------------------------------------------------------------------------------ Layer 0
;; Defaults

(def default-config
  {:agent :planner
   :gates [:plan-complete]
   :budget {:tokens 10000
            :iterations 3
            :time-seconds 300}})

;; Register defaults on load
(registry/register-phase-defaults! :plan default-config)

;------------------------------------------------------------------------------ Layer 1
;; Interceptor implementation

(defn- enter-plan
  "Execute planning phase.

   Reads specification from context, invokes planner agent,
   runs through inner loop with gates."
  [ctx]
  (let [config (registry/merge-with-defaults (get-in ctx [:phase-config]))
        {:keys [agent gates budget]} config
        start-time (System/currentTimeMillis)]
    (-> ctx
        (assoc-in [:phase :name] :plan)
        (assoc-in [:phase :agent] agent)
        (assoc-in [:phase :gates] gates)
        (assoc-in [:phase :budget] budget)
        (assoc-in [:phase :started-at] start-time)
        (assoc-in [:phase :status] :running))))

(defn- leave-plan
  "Post-processing for planning phase.

   Records metrics and updates execution state."
  [ctx]
  (let [start-time (get-in ctx [:phase :started-at])
        end-time (System/currentTimeMillis)
        duration-ms (- end-time start-time)]
    (-> ctx
        (assoc-in [:phase :ended-at] end-time)
        (assoc-in [:phase :duration-ms] duration-ms)
        (assoc-in [:phase :status] :completed)
        (update-in [:execution :phases-completed] (fnil conj []) :plan))))

(defn- error-plan
  "Handle planning phase errors.

   Attempts repair if within budget, otherwise propagates error."
  [ctx ex]
  (let [iterations (get-in ctx [:phase :iterations] 0)
        max-iterations (get-in ctx [:phase :budget :iterations] 3)]
    (if (< iterations max-iterations)
      (-> ctx
          (update-in [:phase :iterations] (fnil inc 0))
          (assoc-in [:phase :last-error] (ex-message ex))
          (assoc-in [:phase :status] :retrying))
      (-> ctx
          (assoc-in [:phase :status] :failed)
          (assoc-in [:phase :error] {:message (ex-message ex)
                                     :data (ex-data ex)})))))

;------------------------------------------------------------------------------ Layer 2
;; Registry method

(defmethod registry/get-phase-interceptor :plan
  [config]
  (let [merged (registry/merge-with-defaults config)]
    {:name ::plan
     :config merged
     :enter (fn [ctx]
              (enter-plan (assoc ctx :phase-config merged)))
     :leave leave-plan
     :error error-plan}))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Get plan interceptor with defaults
  (registry/get-phase-interceptor {:phase :plan})

  ;; Get with overrides
  (registry/get-phase-interceptor {:phase :plan
                                   :budget {:tokens 20000}})

  ;; Check defaults
  (registry/phase-defaults :plan)

  :leave-this-here)
