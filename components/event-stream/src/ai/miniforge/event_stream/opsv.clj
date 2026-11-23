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
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.event-stream.workflow-envelope :as envelope]
   [ai.miniforge.event-stream.schema.opsv :as schema]
   [ai.miniforge.event-stream.messages :as msg]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private identity-keys
  [:org/id :workspace/id :repo/id :auth/context])

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} opsv-event
  [stream workflow-id evidence-id event-type payload-keys message data]
  (let [event (envelope/create stream event-type workflow-id message (select-keys data identity-keys))]
    (if (anomaly/any-anomaly? event) event
        (merge (select-keys data payload-keys) event {:opsv/evidence-bundle-id evidence-id}))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} experiment-planned
  [stream workflow-id evidence-id data]
  (opsv-event stream workflow-id evidence-id :opsv.experiment/planned
              [:opsv/experiment-pack-hash :opsv/targets :opsv/risk-score]
              (msg/t :opsv/experiment-planned {:hash (:opsv/experiment-pack-hash data)})
              data))

(defn ^{:stratum 2} experiment-started
  [stream workflow-id evidence-id data]
  ;; N3 section 3.14 deliberately includes the complete fingerprint value in
  ;; this canonical message as well as in the structured internal payload.
  (opsv-event stream workflow-id evidence-id :opsv.experiment/started
              [:opsv/experiment-pack-hash :opsv/environment-fingerprint]
              (msg/t :opsv/experiment-started {:fingerprint (pr-str (:opsv/environment-fingerprint data))})
              data))

(defn ^{:stratum 2} load-step
  [stream workflow-id evidence-id data]
  ;; N3 section 3.14 deliberately includes both load values in the canonical
  ;; message; consumers that need structure should still use the payload keys.
  (opsv-event stream workflow-id evidence-id :opsv/load-step
              [:opsv/step-id :opsv/intended-load :opsv/observed-load :opsv/metric-snapshot-artifact-refs]
              (msg/t :opsv/load-step {:step (:opsv/step-id data)
                                     :intended (pr-str (:opsv/intended-load data))
                                     :observed (pr-str (:opsv/observed-load data))})
              data))

(defn ^{:stratum 2} guardrail-abort
  [stream workflow-id evidence-id data]
  (opsv-event stream workflow-id evidence-id :opsv.guardrail/abort
              [:opsv/trigger :opsv/threshold :opsv/observed
               :opsv/rollback-action]
              (msg/t :opsv/guardrail-abort {:trigger (:opsv/trigger data)})
              data))

(defn ^{:stratum 2} convergence-iteration
  [stream workflow-id evidence-id data]
  (opsv-event stream workflow-id evidence-id :opsv.convergence/iteration
              [:opsv/iteration-id :opsv/params
               :opsv/observed-metrics-summary :opsv/metric-snapshot-artifact-refs]
              (msg/t :opsv/convergence-iteration {:iteration (:opsv/iteration-id data)})
              data))

(defn ^{:stratum 2} policy-proposed
  [stream workflow-id evidence-id data]
  (opsv-event stream workflow-id evidence-id :opsv.policy/proposed
              [:opsv/policy-hash :opsv/diff-artifact-refs :opsv/confidence :opsv/policy-artifact-ref]
              (msg/t :opsv/policy-proposed {:hash (:opsv/policy-hash data)})
              data))

(defn ^{:stratum 2} verification-result
  [stream workflow-id evidence-id data]
  (opsv-event stream workflow-id evidence-id :opsv.verification/result
              [:opsv/passed? :opsv/criteria-evaluation
               :opsv/confidence :opsv/caveats :opsv/metric-snapshot-artifact-refs]
              (msg/t :opsv/verification-result {:passed? (:opsv/passed? data)})
              data))

(defn ^{:stratum 2} actuation-emitted
  [stream workflow-id evidence-id data]
  (opsv-event stream workflow-id evidence-id :opsv.actuation/emitted
              [:opsv/requested-actuation-mode :opsv/effective-actuation-mode
               :opsv/governed-effects :opsv/pr-refs :opsv/apply-refs]
              (msg/t :opsv/actuation-emitted {:mode (:opsv/effective-actuation-mode data)})
              data))

(defn ^{:stratum 2} actuation-disposition
  [stream workflow-id evidence-id data]
  (opsv-event stream workflow-id evidence-id :opsv.actuation/disposition
              (mapv first schema/disposition-entries)
              (msg/t :opsv/actuation-disposition {:state (:opsv/effect-state data)}) data))

(defn ^{:stratum 2} drift-detected
  [stream workflow-id evidence-id data]
  (opsv-event stream workflow-id evidence-id :opsv.drift/detected
              [:opsv/signal :opsv/deviation :opsv/suggested-rerun?]
              (msg/t :opsv/drift-detected {:signal (:opsv/signal data)})
              data))
