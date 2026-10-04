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
   [ai.miniforge.evidence-bundle.projection :as projection]
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

(defn- ^{:stratum 0} legacy-error-evidence [error-info]
  (when (some? error-info)
    {:outcome/error-message (:message error-info)
     :outcome/error-phase (:phase error-info)
     :outcome/error-details error-info}))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} error-evidence [error-info]
  (projection/present
   (cond
     (any-anomaly? error-info) (response/anomaly->outcome-evidence error-info)
     (any-anomaly? (:anomaly error-info)) (response/anomaly->outcome-evidence (:anomaly error-info))
     :else (legacy-error-evidence error-info))))

(defn ^{:stratum 1} failure-attribution
  [failure]
  (let [attribution (select-keys failure failure-attribution-keys)]
    (when (seq attribution)
      attribution)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} build-outcome-evidence
  "Project final workflow state into N6 outcome evidence without unavailable optional fields."
  [workflow-state]
  (let [success? (= :completed (:workflow/status workflow-state))]
    (merge {:outcome/success success?}
           (projection/pull-request (:workflow/pr-info workflow-state))
           (error-evidence (:workflow/error workflow-state)))))

(defn ^{:stratum 2} collect-failure-attribution
  [workflow-state opts]
  (or (:failure-attribution opts)
      (some-> (:workflow/error workflow-state) failure-attribution)
      (some->> (:workflow/errors workflow-state)
               (keep failure-attribution)
               first)))
