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
(ns ai.miniforge.phase-opsv.lifecycle
  "Shared phase-registry interceptors for the deterministic OPSV pipeline."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.phase.interface :as phase]
   [ai.miniforge.phase-opsv.evidence-runtime :as evidence-runtime]
   [ai.miniforge.phase-opsv.events :as events]
   [ai.miniforge.phase-opsv.lifecycle-result :as lifecycle-result]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} default-config
  {:agent nil
   :gates []
   :budget {:tokens 0 :iterations 1 :time-seconds 300}})

(defn- ^{:stratum 0} isolate-runtime-adapter
  [ctx]
  (let [input (:execution/input ctx)]
    (if-not (contains? input :opsv/adapter)
      ctx
      (let [adapter (:opsv/adapter input)
            durable-ctx (update ctx :execution/input dissoc :opsv/adapter)
            runtime-supplied? (contains? (:execution/opts ctx) :opsv/adapter)]
        (cond-> durable-ctx
          (not runtime-supplied?)
          (assoc-in [:execution/opts :opsv/adapter] adapter))))))

(defn- ^{:stratum 0} leave-phase
  [ctx]
  (let [phase-key (get-in ctx [:phase :name])
        result (get-in ctx [:phase :result])
        success? (phase/result-succeeded? result)
        end-time (System/currentTimeMillis)
        duration-ms (- end-time (get-in ctx [:phase :started-at]))]
    (when success?
      (events/emit-phase-events! ctx phase-key (:output result)))
    (lifecycle-result/complete-phase ctx phase-key success? end-time
                                     duration-ms)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} enter-phase
  [phase-key transform config ctx]
  (let [runtime-ctx (isolate-runtime-adapter ctx)
        prepared-ctx (evidence-runtime/ensure-assembly runtime-ctx)
        start-time (System/currentTimeMillis)
        prepared? (not (anomaly/anomaly? prepared-ctx))
        output (if prepared? (transform prepared-ctx) prepared-ctx)
        result (lifecycle-result/phase-result output)]
    (phase/enter-context (if prepared? prepared-ctx runtime-ctx) phase-key
                         (:agent config)
                         (:gates config) (:budget config)
                         start-time result)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} interceptor
  [config phase-key transform]
  (let [merged (phase/merge-with-defaults config)]
    {:name phase-key
     :config merged
     :enter (partial enter-phase phase-key transform merged)
     :leave leave-phase
     :error lifecycle-result/fail-phase}))
