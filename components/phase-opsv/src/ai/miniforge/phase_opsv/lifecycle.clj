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
   [ai.miniforge.phase-opsv.finalization-config :as finalization-config]
   [ai.miniforge.phase-opsv.terminal-evidence :as terminal]
   [ai.miniforge.phase-opsv.flow :as flow]
   [ai.miniforge.phase-opsv.post-actuation-checkpoint :as checkpoint]
   [ai.miniforge.phase-opsv.lifecycle-result :as lifecycle-result]
   [ai.miniforge.phase-opsv.messages :as msg]))

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

(defn- ^{:stratum 0} gate-failure-result
  "An :opsv/actuate attempt its gates failed, recorded as a failed actuation
   that keeps its output (:opsv/phase-failure names the gate failure). The
   terminal path then journals its events, publishes its material and
   finalizes evidence with :outcome/success false. A result that already
   failed is returned unchanged."
  [phase-result]
  (let [output (:output phase-result)
        message (msg/t :gate/actuation-gate-failed)]
    (if (and (map? output) (not (anomaly/any-anomaly? output)))
      (lifecycle-result/phase-result
       (anomaly/anomaly :unauthorized message
                        {:opsv/phase-output
                         (assoc output :opsv/phase-failure {:anomaly/type :unauthorized
                                                            :anomaly/message message})}))
      phase-result)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} leave-phase
  "A gate failure stops success claims, never the record of what happened.
   Phase events are journaled either way (they record effects that already
   ran). With evidence enabled, an :opsv/actuate attempt its gates failed is
   finalized as a failed actuation, and the gate failure is recorded beside
   the post-actuation snapshot, so recovery cannot finalize it as a success.
   The phase completes as failed."
  [ctx]
  (let [phase-key (get-in ctx [:phase :name])
        gate-failed? (phase-gate/gate-failed? (:phase ctx))
        ;; Without evidence there is nothing to finalize: the actuation's
        ;; events are published normally and the success? guard fails the phase.
        evidence-failure? (and gate-failed? (= :opsv/actuate phase-key)
                               (finalization-config/enabled? ctx))
        raw-result (get-in ctx [:phase :result])
        recorded (if evidence-failure? (gate-failure-result raw-result) raw-result)
        phase-failure (get-in recorded [:output :anomaly/data :opsv/phase-output :opsv/phase-failure])
        recovery-ctx (if (and evidence-failure? phase-failure)
                       (checkpoint/record-gate-failure ctx phase-failure)
                       ctx)
        published (lifecycle-result/after-publication recovery-ctx phase-key recorded)
        result (terminal/complete-failure! recovery-ctx phase-key published)
        completed-ctx (assoc-in recovery-ctx [:phase :result] result)
        success? (and (not gate-failed?) (phase/result-succeeded? result))
        end-time (System/currentTimeMillis)
        duration-ms (- end-time (get-in ctx [:phase :started-at]))]
    (lifecycle-result/complete-phase completed-ctx phase-key success? end-time
                                     duration-ms)))

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
