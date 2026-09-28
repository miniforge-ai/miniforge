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
(ns ai.miniforge.phase-opsv.actuation
  "Evaluate runtime policy, reduce authority and assemble actuation evidence."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.opsv.interface :as opsv]
   [ai.miniforge.phase-opsv.governance :as governance]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} actuation-record
  [requested-mode effective-mode]
  {:requested-actuation-mode requested-mode
   :effective-actuation-mode effective-mode
   :governed-effects []
   :pr-refs []
   :apply-refs []
   :postcondition-artifact-refs []
   :rollback {:status :not-required :artifact-refs []}})

(defn- ^{:stratum 0} attach-record
  [verified record]
  (if (anomaly/anomaly? record)
    record
    (assoc verified :opsv/actuation-record record)))

(defn- ^{:stratum 0} decision-input
  [ctx verification gate-results]
  (let [input (:execution/input ctx)
        requested-mode (get-in input [:opsv/experiment-pack
                                      :experiment-pack/actuation-intent])
        safe-mode? (true? (:opsv/safe-mode? input))]
    {:requested-actuation-mode requested-mode
     :verification-passed? (:passed? verification)
     :gate-results gate-results
     :safe-mode? safe-mode?
     ;; This executor cannot perform governed effects. Input flags are not grants.
     :pr-capability-valid? false
     :apply-capability-valid? false
     :rollback-verified? false
     :postconditions-configured? false}))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} recommendation
  [decision]
  (let [effective-mode (opsv/effective-actuation decision)
        requested-mode (:requested-actuation-mode decision)]
    (if (anomaly/anomaly? effective-mode)
      effective-mode
      (opsv/validate-actuation
       (actuation-record requested-mode effective-mode)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} actuate
  [ctx verified]
  (if (anomaly/anomaly? verified)
    verified
    (let [evaluated (governance/evaluate ctx verified)]
      (if (anomaly/anomaly? evaluated)
        evaluated
        (let [verification (:opsv/verification-result verified)
              decision (decision-input ctx verification (:opsv/gate-results evaluated))
              record (recommendation decision)]
          (attach-record (merge verified evaluated) record))))))

(comment
  (actuation-record :pr-only :recommend-only))
