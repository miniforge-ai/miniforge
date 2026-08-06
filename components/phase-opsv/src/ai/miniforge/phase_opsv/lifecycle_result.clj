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
   [ai.miniforge.phase-opsv.evidence-runtime :as evidence-runtime]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} empty-metrics
  {:tokens 0 :cost-usd 0.0 :duration-ms 0})

(defn ^{:stratum 0} fail-phase
  [ctx ex]
  (assoc ctx :phase
         (phase/fail-phase (:phase ctx) (phase/exception-error ex))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} success
  [output]
  {:status :success :output output :metrics empty-metrics})

(defn- ^{:stratum 1} failure
  [output]
  {:status :error
   :output output
   :error {:message (:anomaly/message output)
           :data (:anomaly/data output)}
   :metrics empty-metrics})

(defn- ^{:stratum 1} completed-metrics
  [duration-ms]
  (assoc empty-metrics :duration-ms duration-ms))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} phase-result
  [output]
  (if (anomaly/anomaly? output) (failure output) (success output)))

(defn ^{:stratum 2} complete-phase
  [ctx phase-key success? end-time duration-ms]
  (cond-> (-> ctx
              evidence-runtime/persist
              (assoc-in [:phase :ended-at] end-time)
              (assoc-in [:phase :duration-ms] duration-ms)
              (assoc-in [:phase :status] (if success? :completed :failed))
              (assoc-in [:phase :metrics] (completed-metrics duration-ms))
              (assoc-in [:phase :result :metrics :duration-ms] duration-ms))
    success?
    (update-in [:execution :phases-completed] (fnil conj []) phase-key)))
