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
(ns ai.miniforge.phase-opsv.adapter
  "Fail-closed validation for OPSV adapter boundaries."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.phase-opsv.messages :as msg]
   [ai.miniforge.phase-opsv.protocol :as port]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} adapter-anomaly
  [candidate]
  (when-not (satisfies? port/OPSVAdapter candidate)
    (anomaly/anomaly :invalid-input (msg/ts :adapter/missing)
                     {:input/key :opsv/adapter})))

(defn- ^{:stratum 0} result-keys
  [result]
  (if (map? result)
    (->> result keys (sort-by pr-str) vec)
    []))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} invalid-ramp-anomaly
  [ramp]
  (cond
    (not (seq (:steps ramp)))
    (anomaly/anomaly :invalid-input (msg/ts :adapter/empty-ramp)
                     {:adapter/result-keys (result-keys ramp)})

    (not (and (map? (:environment-fingerprint ramp))
              (seq (:environment-fingerprint ramp))))
    (anomaly/anomaly :invalid-input (msg/ts :adapter/missing-fingerprint)
                     {:adapter/result-keys (result-keys ramp)})))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} ramp-shape-anomaly
  [ramp]
  (when-not (anomaly/anomaly? ramp)
    (invalid-ramp-anomaly ramp)))
