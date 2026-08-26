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
(ns ai.miniforge.evidence-bundle.outcome
  "Workflow outcome evidence and the attribution of a failure to the
   phase that produced it."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Layer 0

;; Anomaly detection (dual shape during W2 convergence)
(defn- ^{:stratum 0} any-anomaly?
  "True when `x` is either a canonical anomaly (`:anomaly/type`) or a
   legacy response anomaly (`:anomaly/category`).

   evidence-bundle reads anomalies from arbitrary upstream producers
   (workflow/error, error-info :anomaly), so it must detect both
   shapes until W5 retires the legacy producers. Prefers the canonical
   predicate; falls back to the legacy one. Mirrors the dispatch-key
   pattern in `failure-classifier/classify-failure`."
  [x]
  (or (anomaly/anomaly? x)
      (response/anomaly-map? x)))

;; Intent Collection
(def ^{:stratum 0} ^:private failure-attribution-keys
  [:failure/source
   :failure/vendor
   :failure/class
   :failure/message
   :dependency/class
   :dependency/retryability
   :dependency/id
   :dependency/source
   :dependency/kind
   :dependency/vendor
   :dependency/status])

;------------------------------------------------------------------------------ Layer 1

;; Workflow Integration Helpers
(defn ^{:stratum 1} build-outcome-evidence
  "Build outcome evidence from workflow final state.
   Uses anomaly->outcome-evidence when anomaly maps are available.
   Returns outcome evidence per N6 spec."
  [workflow-state]
  (let [status (:workflow/status workflow-state)
        pr-info (:workflow/pr-info workflow-state)
        error-info (:workflow/error workflow-state)
        ;; Check for anomaly map in error-info or workflow state
        ;; (dual-shape during W2: matches both canonical and legacy)
        anomaly-map (cond
                      (any-anomaly? error-info) error-info
                      (any-anomaly? (:anomaly error-info)) (:anomaly error-info)
                      :else nil)]
    (merge
     {:outcome/success (= status :completed)}
     (when pr-info
       {:outcome/pr-number (:number pr-info)
        :outcome/pr-url (:url pr-info)
        :outcome/pr-status (:status pr-info)
        :outcome/pr-merged-at (:merged-at pr-info)})
     (if anomaly-map
       ;; Use boundary translator for anomaly maps
       (response/anomaly->outcome-evidence anomaly-map)
       ;; Fall back to legacy error shape
       (when error-info
         {:outcome/error-message (:message error-info)
          :outcome/error-phase (:phase error-info)
          :outcome/error-details error-info})))))

(defn ^{:stratum 1} failure-attribution
  [failure]
  (let [attribution (select-keys failure failure-attribution-keys)]
    (when (seq attribution)
      attribution)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} collect-failure-attribution
  [workflow-state opts]
  (or (:failure-attribution opts)
      (some-> (:workflow/error workflow-state) failure-attribution)
      (some->> (:workflow/errors workflow-state)
               (keep failure-attribution)
               first)))
