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
  "Fail-closed OPSV authority reduction and recommend-only record assembly."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.opsv.interface :as opsv]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} input-value
  [ctx key]
  (get-in ctx [:execution/input key]))

(def ^{:stratum 0} ^:private default-gate-results
  [{:gate/id :instrumentation :gate/passed? false}
   {:gate/id :environment :gate/passed? false}
   {:gate/id :blast-radius :gate/passed? false}
   {:gate/id :abort :gate/passed? false}
   {:gate/id :actuation :gate/passed? false}
   {:gate/id :evidence-completeness :gate/passed? false}])

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} decision-input
  [ctx verification]
  (let [pack (input-value ctx :opsv/experiment-pack)]
    {:requested-actuation-mode (:experiment-pack/actuation-intent pack)
     :verification-passed? (:passed? verification)
     :gate-results (get-in ctx [:execution/input :opsv/gate-results]
                           default-gate-results)
     :safe-mode? (true? (input-value ctx :opsv/safe-mode?))
     :pr-capability-valid? (true? (input-value ctx :opsv/pr-capability-valid?))
     :apply-capability-valid? (true? (input-value ctx :opsv/apply-capability-valid?))
     :rollback-verified? (true? (input-value ctx :opsv/rollback-verified?))
     :postconditions-configured?
     (true? (input-value ctx :opsv/postconditions-configured?))}))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} actuate
  [ctx verified]
  (let [verification (:opsv/verification-result verified)
        effective-mode (opsv/effective-actuation
                        (decision-input ctx verification))]
    (if (anomaly/anomaly? effective-mode)
      effective-mode
      (let [record {:requested-actuation-mode
                    (:experiment-pack/actuation-intent verified)
                    :effective-actuation-mode effective-mode
                    :governed-effects []
                    :pr-refs []
                    :apply-refs []
                    :postcondition-artifact-refs []
                    :rollback {:status :not-required :artifact-refs []}}
            validated (opsv/validate-actuation record)]
        (if (anomaly/anomaly? validated)
          validated
          (assoc verified :opsv/actuation-record validated))))))
