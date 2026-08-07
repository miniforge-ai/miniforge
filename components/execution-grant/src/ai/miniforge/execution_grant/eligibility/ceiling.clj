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
(ns ai.miniforge.execution-grant.eligibility.ceiling
  "Ceilings a principal's breach history permits them to receive."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.execution-grant.breach :as breach]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} breach-for-axis?
  [effect-class axis record]
  (and (= effect-class (:breach/effect-class record))
       (= axis (:breach/axis record))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} minimum-relevant-limit
  [records effect-class axis]
  (let [limits (into []
                     (comp (filter (partial breach-for-axis? effect-class axis))
                           (map :breach/limit))
                     records)]
    (when (seq limits) (apply min limits))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} permitted-ceiling
  "The highest ceiling this principal may now receive on `axis`.

   No relevant breach means unbounded authority is still eligible.
   Otherwise the lowest breached limit becomes an exclusive ceiling:
   recovery is possible through narrower authority, not an immediate
   restoration of the same bound. Storage anomalies pass through so
   issuance can fail closed."
  [dir principal effect-class axis]
  (let [records (breach/history dir principal)]
    (if (anomaly/anomaly? records)
      records
      (minimum-relevant-limit records effect-class axis))))
