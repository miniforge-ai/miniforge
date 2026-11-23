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
(ns ai.miniforge.pipeline-runner.stage-result
  "Stage result values shared by every stage executor: the result map, its
   time window, and structured exception and anomaly context."
  (:import [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private max-exception-causes
  "Maximum number of nested exception causes preserved in stage failure results."
  16)

(defn ^{:stratum 0} stage-result
  "Build a stage result map. Merges extras onto the base."
  [id name status extras]
  (merge {:stage/id id :stage/name name :status status :retry-count 0}
         extras))

(defn ^{:stratum 0} timestamps
  "Build the {:started-at :completed-at} window for a just-completed stage.
   started-at defaults to now (transform/validate stages); pass a value
   from context to honor an existing window (ingest stage)."
  ([] (timestamps nil))
  ([started-at]
   (let [now (Instant/now)]
     {:started-at   (or started-at now)
      :completed-at now})))

(defn ^{:stratum 0} anomaly-context
  [anomaly]
  {:error-message    (:anomaly/message anomaly)
   :anomaly/category (:anomaly/category anomaly)
   :anomaly          anomaly})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} exception-context
  "Build structured exception metadata for failed stage results."
  [^Throwable e]
  (let [bounded-causes (->> (iterate ex-cause e)
                            rest
                            (take-while some?)
                            (take (inc max-exception-causes))
                            vec)
        truncated?     (> (count bounded-causes) max-exception-causes)
        causes         (->> bounded-causes
                            (take max-exception-causes)
                            (mapv (fn [^Throwable cause]
                                    {:error-message (ex-message cause)
                                     :error-type    (.getName (class cause))})))]
    (cond-> {:error-message (ex-message e)
             :error-type    (.getName (class e))
             :error-cause   (:error-message (first causes))
             :error-causes  causes}
      truncated? (assoc :error-causes-truncated? true))))

(comment
  (stage-result (random-uuid) "Ingest" :completed (timestamps))
  (exception-context (ex-info "outer" {} (ex-info "inner" {}))))
