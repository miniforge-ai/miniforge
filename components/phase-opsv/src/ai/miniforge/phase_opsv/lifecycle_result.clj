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
(ns ai.miniforge.phase-opsv.lifecycle-result
  "Construct shared lifecycle result and completion state for OPSV phases."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.phase.interface :as phase]
   [ai.miniforge.phase-opsv.evidence-checkpoint :as checkpoint]
   [ai.miniforge.phase-opsv.evidence-runtime :as evidence-runtime]
   [ai.miniforge.phase-opsv.lifecycle-outcome :as outcome]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} phase-result outcome/phase-result)

(defn ^{:stratum 0} fail-phase
  [ctx ex]
  (assoc ctx :phase
         (phase/fail-phase (:phase ctx) (phase/exception-error ex))))

(defn- ^{:stratum 0} completed-metrics
  [duration-ms]
  (assoc outcome/empty-metrics :duration-ms duration-ms))

(defn- ^{:stratum 0} persist-evidence [ctx]
  (let [persisted (evidence-runtime/persist ctx)
        snapshot (checkpoint/persistence-failure persisted)
        output (get-in ctx [:phase :result :output])
        retained (get-in output [:anomaly/data :opsv/phase-output] output)]
    (if (anomaly/anomaly? snapshot)
      (assoc-in persisted [:phase :result]
                (outcome/phase-result (assoc-in snapshot [:anomaly/data :opsv/phase-output]
                                   retained)))
      persisted)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} complete-phase
  [ctx phase-key success? end-time duration-ms]
  (let [persisted (persist-evidence ctx)
        success? (and success? (= :success (get-in persisted [:phase :result :status])))]
    (cond-> (-> persisted
              (assoc-in [:phase :ended-at] end-time)
              (assoc-in [:phase :duration-ms] duration-ms)
              (assoc-in [:phase :status] (if success? :completed :failed))
              (assoc-in [:phase :metrics] (completed-metrics duration-ms))
              (assoc-in [:phase :result :metrics :duration-ms] duration-ms))
    success?
      (update-in [:execution :phases-completed] (fnil conj []) phase-key))))
