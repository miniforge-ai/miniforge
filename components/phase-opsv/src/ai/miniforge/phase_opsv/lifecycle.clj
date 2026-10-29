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
   [ai.miniforge.phase.interface.gate :as phase-gate]
   [ai.miniforge.phase-opsv.artifact-boundary :as artifacts]
   [ai.miniforge.phase-opsv.artifact-model :as artifact-model]
   [ai.miniforge.phase-opsv.evidence-runtime :as evidence-runtime]
   [ai.miniforge.phase-opsv.finalization-boundary :as finalization]
   [ai.miniforge.phase-opsv.terminal-evidence :as terminal]
   [ai.miniforge.phase-opsv.flow :as flow]
   [ai.miniforge.phase-opsv.post-actuation-checkpoint :as checkpoint]
   [ai.miniforge.phase-opsv.lifecycle-result :as lifecycle-result]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} default-config
  {:agent nil
   :phase/expected-agent :agent/none
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

(defn- ^{:stratum 0} transformed-phase [ctx phase-key transform config start-time]
  (let [transformed (artifact-model/without-attempt-receipts (transform ctx))
        output (checkpoint/retain-events ctx phase-key transformed)
        retained (checkpoint/capture ctx phase-key output)
        published (or (checkpoint/encoding-failure retained output)
                      (artifacts/publish-with-exception-handling retained phase-key output))]
    (phase/enter-context retained phase-key (:agent config) (:gates config)
                         (:budget config) start-time (lifecycle-result/phase-result published))))

(defn- ^{:stratum 0} leave-phase
  "A phase its gates failed publishes no success events, is not finalized and
   completes as failed."
  [ctx]
  (let [phase-key (get-in ctx [:phase :name])
        gate-failed? (phase-gate/gate-failed? (:phase ctx))
        raw-result (get-in ctx [:phase :result])
        published (if gate-failed?
                    raw-result
                    (lifecycle-result/after-publication ctx phase-key raw-result))
        result (terminal/complete-failure! ctx phase-key published)
        completed-ctx (assoc-in ctx [:phase :result] result)
        success? (and (not gate-failed?) (phase/result-succeeded? result))
        end-time (System/currentTimeMillis)
        duration-ms (- end-time (get-in ctx [:phase :started-at]))]
    (lifecycle-result/complete-phase completed-ctx phase-key success? end-time
                                     duration-ms)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} enter-phase
  [phase-key transform config ctx]
  (let [runtime-ctx (isolate-runtime-adapter ctx)
        assembled-ctx (evidence-runtime/ensure-assembly runtime-ctx)
        active-ctx (flow/continue assembled-ctx artifacts/prepare)
        prepared-ctx (flow/continue active-ctx finalization/prepare)
        start-time (System/currentTimeMillis)
        prepared? (not (anomaly/anomaly? prepared-ctx))]
    (if prepared?
      (transformed-phase prepared-ctx phase-key transform config start-time)
      (phase/enter-context runtime-ctx phase-key (:agent config) (:gates config)
                           (:budget config) start-time (lifecycle-result/phase-result prepared-ctx)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} interceptor
  [config phase-key transform]
  (let [merged (phase/merge-with-defaults config)]
    {:name phase-key
     :config merged
     :enter (partial enter-phase phase-key transform merged)
     :leave leave-phase
     :error lifecycle-result/fail-phase}))

(comment
  (interceptor {} :opsv/plan identity))
