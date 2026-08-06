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
(ns ai.miniforge.phase-opsv.event-projection
  "Construct N3 events for successful OPSV phase outputs."
  (:require
   [ai.miniforge.event-stream.interface :as event-stream]
   [ai.miniforge.phase-opsv.confidence :as confidence]
   [ai.miniforge.phase-opsv.load-events :as load-events]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} policy-confidence
  [output]
  (confidence/level
   (get-in output [:opsv/convergence-result :evaluation :confidence])
   (get-in output [:opsv/experiment-pack
                   :experiment-pack/convergence
                   :confidence-threshold])))

(defn- ^{:stratum 0} convergence-event
  [stream workflow-id evidence-id index step]
  (event-stream/convergence-iteration
   stream workflow-id evidence-id
   {:opsv/iteration-id (str (inc index))
    :opsv/params (:step/intended-load step)
    :opsv/observed-metrics-summary (:step/metrics step)}))

(defn- ^{:stratum 0} planned-events
  [stream workflow-id evidence-id output]
  [(event-stream/experiment-planned
    stream workflow-id evidence-id
    {:opsv/experiment-pack-hash (:opsv/experiment-pack-hash output)
     :opsv/targets (get-in output [:opsv/experiment-pack
                                   :experiment-pack/targets])
     :opsv/risk-score (:opsv/risk-result output)})])

(defn- ^{:stratum 0} execution-events
  [stream workflow-id evidence-id output]
  (let [started (event-stream/experiment-started
                 stream workflow-id evidence-id
                 {:opsv/experiment-pack-hash
                  (:opsv/experiment-pack-hash output)
                  :opsv/environment-fingerprint
                  (:opsv/environment-fingerprint output)})]
    (into [started]
          (load-events/project stream workflow-id evidence-id
                               (:opsv/ramp-steps output)))))

(defn- ^{:stratum 0} verification-events
  [stream workflow-id evidence-id output]
  (let [verification (:opsv/verification-result output)]
    [(event-stream/verification-result
      stream workflow-id evidence-id
      {:opsv/passed? (:passed? verification)
       :opsv/criteria-evaluation (:criteria-evaluation verification)
       :opsv/confidence (:confidence verification)
       :opsv/caveats (:caveats verification)})]))

(defn- ^{:stratum 0} actuation-events
  [stream workflow-id evidence-id output]
  (let [actuation (:opsv/actuation-record output)]
    [(event-stream/actuation-emitted
      stream workflow-id evidence-id
      {:opsv/requested-actuation-mode (:requested-actuation-mode actuation)
       :opsv/effective-actuation-mode (:effective-actuation-mode actuation)
       :opsv/governed-effects (:governed-effects actuation)
       :opsv/pr-refs (:pr-refs actuation)
       :opsv/apply-refs (:apply-refs actuation)})]))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} convergence-events
  [stream workflow-id evidence-id output]
  (let [history (get-in output [:opsv/convergence-result :state :history])]
    (map-indexed (partial convergence-event stream workflow-id evidence-id)
                 history)))

(defn- ^{:stratum 1} policy-events
  [stream workflow-id evidence-id ctx output]
  [(event-stream/policy-proposed
    stream workflow-id evidence-id
    {:opsv/policy-hash (:opsv/policy-hash output)
     :opsv/diff-artifact-refs
     (get-in ctx [:execution/input :opsv/policy-diff-artifact-refs] [])
     :opsv/confidence (policy-confidence output)})])

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} phase-events
  [stream workflow-id evidence-id ctx phase-key output]
  (case phase-key
    :opsv/discover []
    :opsv/plan (planned-events stream workflow-id evidence-id output)
    :opsv/execute (execution-events stream workflow-id evidence-id output)
    :opsv/converge (convergence-events stream workflow-id evidence-id output)
    :opsv/synthesize (policy-events stream workflow-id evidence-id ctx output)
    :opsv/verify (verification-events stream workflow-id evidence-id output)
    :opsv/actuate (actuation-events stream workflow-id evidence-id output)
    []))
