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
(ns ai.miniforge.gate.opsv
  "Registry adapter for the six N4 OPSV gates."
  (:require
   [ai.miniforge.gate.opsv.core :as opsv]
   [ai.miniforge.gate.registry :as registry]
   [ai.miniforge.policy-pack.interface :as policy-pack]))

;------------------------------------------------------------------------------ Layer 0

(defmethod ^{:stratum 0} registry/get-gate :opsv/instrumentation-gate [_]
  (opsv/gate-definition :instrumentation opsv/check-instrumentation))

(defmethod ^{:stratum 0} registry/get-gate :opsv/environment-gate [_]
  (opsv/gate-definition :environment opsv/check-environment))

(defmethod ^{:stratum 0} registry/get-gate :opsv/blast-radius-gate [_]
  (opsv/gate-definition :blast-radius opsv/check-blast-radius))

(defmethod ^{:stratum 0} registry/get-gate :opsv/abort-gate [_]
  (opsv/gate-definition :abort opsv/check-abort))

(defmethod ^{:stratum 0} registry/get-gate :opsv/actuation-gate [_]
  (opsv/gate-definition :actuation opsv/check-actuation))

(defmethod ^{:stratum 0} registry/get-gate :opsv/evidence-completeness-gate [_]
  (opsv/gate-definition :evidence-completeness
                        opsv/check-evidence-completeness))

(defn- ^{:stratum 0} detector
  [check]
  (fn [artifact ctx]
    (let [result (check artifact ctx)]
      (when-not (:passed? result) (first (:errors result))))))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} ^:private policy-detectors
  {'ai.miniforge.gate.opsv/instrumentation-detector
   (detector opsv/check-instrumentation)
   'ai.miniforge.gate.opsv/environment-detector
   (detector opsv/check-environment)
   'ai.miniforge.gate.opsv/blast-radius-detector
   (detector opsv/check-blast-radius)
   'ai.miniforge.gate.opsv/abort-detector
   (detector opsv/check-abort)
   'ai.miniforge.gate.opsv/actuation-detector
   (detector opsv/check-actuation)
   'ai.miniforge.gate.opsv/evidence-completeness-detector
   (detector opsv/check-evidence-completeness)})

(doseq [gate-key [:opsv/instrumentation-gate :opsv/environment-gate
                  :opsv/blast-radius-gate :opsv/abort-gate
                  :opsv/actuation-gate :opsv/evidence-completeness-gate]]
  (registry/register-gate! gate-key))

(doseq [[detector-symbol check] policy-detectors]
  (policy-pack/register-custom-fn! detector-symbol check))
