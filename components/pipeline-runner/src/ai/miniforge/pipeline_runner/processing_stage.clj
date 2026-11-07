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
(ns ai.miniforge.pipeline-runner.processing-stage
  "Transform and validate stage executors: stages that process the records
   collected from upstream stages without a connector."
  (:require [ai.miniforge.data-quality.interface :as dq]
            [ai.miniforge.pipeline-runner.stage-result
             :refer [stage-result timestamps exception-context]]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} execute-transform-stage
  "Execute a non-connector stage (normalize, transform, aggregate, etc.).
   If :stage/transform config with :transform/type is present and a matching
   transform fn exists in context :transforms, calls it with input records.
   Otherwise passes records through unchanged."
  [{:stage/keys [id name config]} context input-records]
  (let [transform-type (get-in config [:stage/transform :transform/type])
        transform-fn   (when transform-type
                         (get-in context [:transforms transform-type]))]
    (if transform-fn
      (try
        (let [result-records (transform-fn input-records config)]
          (stage-result id name :completed
                        (assoc (timestamps) :records result-records)))
        (catch Exception e
          (stage-result id name :failed (exception-context e))))
      (stage-result id name :completed
                    (assoc (timestamps) :records input-records)))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} execute-validate-stage
  "Execute a validate stage using data-quality rules.
   If :stage/quality-pack is present in config, evaluates records and filters.
   Otherwise falls through to transform (pass-through)."
  [{:stage/keys [id name config]} context input-records]
  (if-let [quality-pack (:stage/quality-pack config)]
    (let [evaluation (dq/evaluate-records (:pack/rules quality-pack) input-records)
          passed     (dq/filter-passed evaluation)
          report     (dq/generate-report (:pack/id quality-pack) evaluation)]
      (stage-result id name :completed
                    (merge (timestamps)
                           {:records passed
                            :quality-report report})))
    (execute-transform-stage {:stage/id id :stage/name name :stage/config config} context input-records)))
