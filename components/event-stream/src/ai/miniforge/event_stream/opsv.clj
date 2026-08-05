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
(ns ai.miniforge.event-stream.opsv
  "N3 OPSV event constructors with preallocated N6 bundle correlation."
  (:require
   [ai.miniforge.event-stream.core :as core]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private identity-keys
  [:org/id :workspace/id :repo/id :auth/context])

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} opsv-event
  [stream workflow-id evidence-id event-type payload-keys message data]
  (merge (select-keys data payload-keys)
         (core/create-envelope stream event-type workflow-id message
                               (select-keys data identity-keys))
         {:opsv/evidence-bundle-id evidence-id}))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} experiment-planned
  [stream workflow-id evidence-id data]
  (opsv-event stream workflow-id evidence-id :opsv.experiment/planned
              [:opsv/experiment-pack-hash :opsv/targets :opsv/risk-score]
              (str "OPSV experiment planned: " (:opsv/experiment-pack-hash data))
              data))

(defn ^{:stratum 2} experiment-started
  [stream workflow-id evidence-id data]
  (opsv-event stream workflow-id evidence-id :opsv.experiment/started
              [:opsv/experiment-pack-hash :opsv/environment-fingerprint]
              (str "OPSV experiment started in "
                   (pr-str (:opsv/environment-fingerprint data)))
              data))

(defn ^{:stratum 2} load-step
  [stream workflow-id evidence-id data]
  (opsv-event stream workflow-id evidence-id :opsv/load-step
              [:opsv/step-id :opsv/intended-load :opsv/observed-load]
              (str "OPSV load step " (:opsv/step-id data) ": "
                   (pr-str (:opsv/intended-load data)) " → "
                   (pr-str (:opsv/observed-load data)))
              data))

(defn ^{:stratum 2} guardrail-abort
  [stream workflow-id evidence-id data]
  (opsv-event stream workflow-id evidence-id :opsv.guardrail/abort
              [:opsv/trigger :opsv/threshold :opsv/observed
               :opsv/rollback-action]
              (str "OPSV guardrail abort: " (:opsv/trigger data))
              data))

(defn ^{:stratum 2} convergence-iteration
  [stream workflow-id evidence-id data]
  (opsv-event stream workflow-id evidence-id :opsv.convergence/iteration
              [:opsv/iteration-id :opsv/params
               :opsv/observed-metrics-summary]
              (str "OPSV convergence iteration " (:opsv/iteration-id data))
              data))

(defn ^{:stratum 2} policy-proposed
  [stream workflow-id evidence-id data]
  (opsv-event stream workflow-id evidence-id :opsv.policy/proposed
              [:opsv/policy-hash :opsv/diff-artifact-refs :opsv/confidence]
              (str "OPSV policy proposed: " (:opsv/policy-hash data))
              data))

(defn ^{:stratum 2} verification-result
  [stream workflow-id evidence-id data]
  (opsv-event stream workflow-id evidence-id :opsv.verification/result
              [:opsv/passed? :opsv/criteria-evaluation
               :opsv/confidence :opsv/caveats]
              (str "OPSV verification result: " (:opsv/passed? data))
              data))

(defn ^{:stratum 2} actuation-emitted
  [stream workflow-id evidence-id data]
  (opsv-event stream workflow-id evidence-id :opsv.actuation/emitted
              [:opsv/requested-actuation-mode :opsv/effective-actuation-mode
               :opsv/governed-effects :opsv/pr-refs :opsv/apply-refs]
              (str "OPSV actuation emitted: "
                   (:opsv/effective-actuation-mode data))
              data))

(defn ^{:stratum 2} drift-detected
  [stream workflow-id evidence-id data]
  (opsv-event stream workflow-id evidence-id :opsv.drift/detected
              [:opsv/signal :opsv/deviation :opsv/suggested-rerun?]
              (str "OPSV drift detected: " (:opsv/signal data))
              data))
