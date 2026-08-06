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
(ns ai.miniforge.phase-opsv.events
  "N3 OPSV event projection and publication at successful phase boundaries."
  (:require
   [ai.miniforge.evidence-bundle.interface :as evidence]
   [ai.miniforge.event-stream.interface :as event-stream]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} confidence-level
  [output]
  (let [score (get-in output [:opsv/convergence-result :evaluation :confidence])
        threshold (get-in output [:opsv/experiment-pack
                                  :experiment-pack/convergence
                                  :confidence-threshold])]
    (if (and (number? score)
             (number? threshold)
             (>= score threshold))
      :high
      :low)))

(defn- ^{:stratum 0} stream
  [ctx]
  (or (:event-stream ctx)
      (:execution/event-stream ctx)
      (get-in ctx [:execution/opts :event-stream])))

(defn- ^{:stratum 0} workflow-id
  [ctx]
  (or (:execution/id ctx) (:workflow/id ctx) (:workflow-id ctx)))

(defn- ^{:stratum 0} evidence-id
  [ctx]
  (get-in ctx [:execution/input :opsv/evidence-bundle-id]))

(defn- ^{:stratum 0} load-step-events
  [stream-value workflow-id evidence-id steps]
  (mapcat
   (fn [step]
     (let [step-event
           (event-stream/load-step
            stream-value workflow-id evidence-id
            {:opsv/step-id (:step/id step)
             :opsv/intended-load (:step/intended-load step)
             :opsv/observed-load (:step/observed-load step)})
           abort? (true? (get-in step [:step/metrics :guardrail-abort?]))
           abort-data (:step/guardrail-abort step)]
       (cond-> [step-event]
         abort?
         (conj
          (event-stream/guardrail-abort
           stream-value workflow-id evidence-id
           {:opsv/trigger (get abort-data :trigger :guardrail)
            :opsv/threshold (get abort-data :threshold {})
            :opsv/observed (get abort-data :observed (:step/observations step))
            :opsv/rollback-action
            (get abort-data :rollback-action :restore-previous-policy)})))))
   steps))

(defn- ^{:stratum 0} convergence-events
  [stream-value workflow-id evidence-id output]
  (map-indexed
   (fn [index step]
     (event-stream/convergence-iteration
      stream-value workflow-id evidence-id
      {:opsv/iteration-id (str (inc index))
       :opsv/params (:step/intended-load step)
       :opsv/observed-metrics-summary (:step/metrics step)}))
   (get-in output [:opsv/convergence-result :state :history])))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} phase-events
  [stream-value workflow-id evidence-id ctx phase-key output]
  (case phase-key
    :opsv/discover []
    :opsv/plan
    [(event-stream/experiment-planned
      stream-value workflow-id evidence-id
      {:opsv/experiment-pack-hash (:opsv/experiment-pack-hash output)
       :opsv/targets (get-in output [:opsv/experiment-pack
                                     :experiment-pack/targets])
       :opsv/risk-score (:opsv/risk-result output)})]
    :opsv/execute
    (into
     [(event-stream/experiment-started
       stream-value workflow-id evidence-id
       {:opsv/experiment-pack-hash (:opsv/experiment-pack-hash output)
        :opsv/environment-fingerprint (:opsv/environment-fingerprint output)})]
     (load-step-events stream-value workflow-id evidence-id
                       (:opsv/ramp-steps output)))
    :opsv/converge
    (convergence-events stream-value workflow-id evidence-id output)
    :opsv/synthesize
    [(event-stream/policy-proposed
      stream-value workflow-id evidence-id
      {:opsv/policy-hash (:opsv/policy-hash output)
       :opsv/diff-artifact-refs
       (get-in ctx [:execution/input :opsv/policy-diff-artifact-refs] [])
       :opsv/confidence (confidence-level output)})]
    :opsv/verify
    (let [verification (:opsv/verification-result output)]
      [(event-stream/verification-result
        stream-value workflow-id evidence-id
        {:opsv/passed? (:passed? verification)
         :opsv/criteria-evaluation (:criteria-evaluation verification)
         :opsv/confidence (:confidence verification)
         :opsv/caveats (:caveats verification)})])
    :opsv/actuate
    (let [actuation (:opsv/actuation-record output)]
      [(event-stream/actuation-emitted
        stream-value workflow-id evidence-id
        {:opsv/requested-actuation-mode (:requested-actuation-mode actuation)
         :opsv/effective-actuation-mode (:effective-actuation-mode actuation)
         :opsv/governed-effects (:governed-effects actuation)
         :opsv/pr-refs (:pr-refs actuation)
         :opsv/apply-refs (:apply-refs actuation)})])
    []))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} emit-phase-events!
  [ctx phase-key output]
  (when-let [stream-value (stream ctx)]
    (try
      (doseq [event (phase-events stream-value (workflow-id ctx)
                                  (evidence-id ctx) ctx phase-key output)]
        (let [published (event-stream/publish! stream-value event)]
          (when (and (= (:event/id event) (:event/id published))
                     (:opsv/evidence-assembly-store ctx))
            (evidence/accumulate-opsv-evidence!
             (:opsv/evidence-assembly-store ctx)
             (evidence-id ctx)
             {:opsv/event-refs [(:event/id event)]}))))
      (catch Exception _ nil))))
