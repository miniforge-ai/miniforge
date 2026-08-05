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
(ns ai.miniforge.phase-opsv.risk
  "Transparent OPSV risk inputs derived from an Experiment Pack."
  (:require [ai.miniforge.phase-opsv.messages :as msg]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private environment-contributions
  {"staging" 0.05 "production-canary" 0.2 "production" 0.4})

(def ^{:stratum 0} ^:private actuation-contributions
  {:recommend-only 0.0 :pr-only 0.15 :apply-allowed 0.3})

(def ^{:stratum 0} ^:private criticality-contributions
  {:low 0.0 :medium 0.05 :high 0.15 :critical 0.25})

(defn- ^{:stratum 0} numeric-contribution
  [value multiplier]
  (if (and (number? value) (not (neg? value)))
    (* multiplier (double value))
    0.25))

(defn- ^{:stratum 0} factor
  [name input contribution rationale]
  {:factor name :input input :contribution contribution
   :rationale (msg/t rationale)})

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} environment-contribution
  [values]
  (if (seq values)
    (reduce max 0.0 (map #(get environment-contributions % 0.4) values))
    0.4))

(defn- ^{:stratum 1} blast-radius-contribution
  [blast-radius]
  (let [namespaces (:namespaces blast-radius)
        namespace-risk (if (coll? namespaces)
                         (* 0.01 (count namespaces))
                         0.25)]
    (min 0.25 (+ (numeric-contribution (:replica-delta blast-radius) 0.02)
                 (numeric-contribution (:node-delta blast-radius) 0.03)
                 namespace-risk))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} factors
  [pack service-criticality]
  (let [environments (get-in pack [:experiment-pack/targets :environments])
        blast-radius (get-in pack [:experiment-pack/guardrails :blast-radius])
        actuation (:experiment-pack/actuation-intent pack)
        base [(factor :environment-class environments
                      (environment-contribution environments)
                      :risk/environment-class)
              (factor :blast-radius blast-radius
                      (blast-radius-contribution blast-radius)
                      :risk/blast-radius)
              (factor :actuation-requested actuation
                      (get actuation-contributions actuation 0.0)
                      :risk/actuation)]]
    (cond-> base
      service-criticality
      (conj (factor :service-criticality service-criticality
                    (get criticality-contributions service-criticality 0.25)
                    :risk/service-criticality)))))
