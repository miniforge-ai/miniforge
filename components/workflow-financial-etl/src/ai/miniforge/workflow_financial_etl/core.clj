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

(ns ai.miniforge.workflow-financial-etl.core
  "ETL execution helpers owned by the financial ETL product."
  (:require
   [ai.miniforge.logging.interface :as logging]
   [ai.miniforge.schema.interface :as schema]
   [ai.miniforge.workflow-financial-etl.events :as events]))

(defn create-etl-state
  [workflow-id sources]
  {:workflow/id workflow-id
   :workflow/type :etl
   :workflow/status :running
   :workflow/started-at (java.util.Date.)
   :etl/sources sources
   :etl/stats {:packs-generated 0
               :packs-promoted 0
               :high-risk-findings 0
               :sources-processed 0}})

(defn classify-sources
  [logger sources]
  (logging/debug logger :etl :etl.classification/started
                 {:data {:source-count (count sources)}})
  (try
    (let [classified sources]
      (logging/debug logger :etl :etl.classification/completed
                     {:data {:classified-count (count classified)}})
      (schema/success :classified classified))
    (catch Exception e
      (logging/error logger :etl :etl.classification/failed
                     {:message (ex-message e)
                      :data {:error-type (type e)}})
      (schema/exception-failure :classified e {:stage :classification}))))

(defn scan-sources
  [logger classified-sources]
  (logging/debug logger :etl :etl.scanning/started
                 {:data {:source-count (count classified-sources)}})
  (try
    (let [scanned classified-sources
          findings []]
      (logging/debug logger :etl :etl.scanning/completed
                     {:data {:scanned-count (count scanned)
                             :findings-count (count findings)}})
      (schema/success :scanned scanned {:findings findings}))
    (catch Exception e
      (logging/error logger :etl :etl.scanning/failed
                     {:message (ex-message e)
                      :data {:error-type (type e)}})
      (schema/exception-failure :scanned e {:stage :scanning}))))

(defn extract-knowledge
  [logger scanned-sources]
  (logging/debug logger :etl :etl.extraction/started
                 {:data {:source-count (count scanned-sources)}})
  (try
    (let [packs []]
      (logging/debug logger :etl :etl.extraction/completed
                     {:data {:pack-count (count packs)}})
      (schema/success :packs packs))
    (catch Exception e
      (logging/error logger :etl :etl.extraction/failed
                     {:message (ex-message e)
                      :data {:error-type (type e)}})
      (schema/exception-failure :packs e {:stage :extraction}))))

(defn validate-packs
  [logger packs]
  (logging/debug logger :etl :etl.validation/started
                 {:data {:pack-count (count packs)}})
  (try
    (let [validated packs]
      (logging/debug logger :etl :etl.validation/completed
                     {:data {:validated-count (count validated)}})
      (schema/success :validated validated))
    (catch Exception e
      (logging/error logger :etl :etl.validation/failed
                     {:message (ex-message e)
                      :data {:error-type (type e)}})
      (schema/exception-failure :validated e {:stage :validation}))))

(defn stage-result
  [stage-id result]
  {:stage/id stage-id
   :stage/result result})

(defn stage-output
  [pipeline-results stage-id output-key]
  (get-in pipeline-results [stage-id :stage/result output-key]))

(defn execute-stage
  [logger {:keys [id input run]} pipeline]
  (let [result (run logger (input pipeline))]
    (assoc-in pipeline [:results id] (stage-result id result))))

(defn high-risk-findings
  [findings]
  (count (filter #(= :high (:severity %)) findings)))

(defn stage-failure
  [logger workflow-id start-time state stage-entry details]
  (let [duration (- (System/currentTimeMillis) start-time)
        result (:stage/result stage-entry)
        error (:error result)]
    (events/emit-etl-failed logger workflow-id
                            (:stage error)
                            (:message error)
                            (assoc details :duration-ms duration))
    (schema/failure nil error
                    {:duration-ms duration
                     :stats (merge (:etl/stats state)
                                   (:stats details {}))})))

(defn successful-stage?
  [pipeline stage-id]
  (get-in pipeline [:results stage-id :stage/result :success?]))

(def stage-definitions
  [{:id :classification
    :input (fn [pipeline] (:workflow/input pipeline))
    :run (fn [logger input] (classify-sources logger input))}
   {:id :scanning
    :input (fn [{:keys [results]}]
             (stage-output results :classification :classified))
    :run (fn [logger input] (scan-sources logger input))}
   {:id :extraction
    :input (fn [{:keys [results]}]
             (stage-output results :scanning :scanned))
    :run (fn [logger input] (extract-knowledge logger input))}
   {:id :validation
    :input (fn [{:keys [results]}]
             (stage-output results :extraction :packs))
    :run (fn [logger input] (validate-packs logger input))}])

(defn run-pipeline
  [logger sources]
  (reduce (fn [pipeline stage]
            (if (:failure-stage pipeline)
              (reduced pipeline)
              (let [next-pipeline (execute-stage logger stage pipeline)
                    stage-entry (get-in next-pipeline [:results (:id stage)])]
                (if (:success? (:stage/result stage-entry))
                  next-pipeline
                  (reduced (assoc next-pipeline :failure-stage (:id stage)))))))
          {:workflow/input sources
           :results {}}
          stage-definitions))

(defn build-success-stats
  [sources pipeline]
  (let [validated-packs (stage-output (:results pipeline) :validation :validated)
        findings (get-in pipeline [:results :scanning :stage/result :findings] [])
        high-risk-count (high-risk-findings findings)]
    {:packs-generated (count validated-packs)
     :packs-promoted 0
     :high-risk-findings high-risk-count
     :sources-processed (count sources)}))

(defn run-etl-workflow
  [logger workflow-id sources]
  (logging/info logger :etl :etl.workflow/started
                {:data {:workflow-id workflow-id
                        :source-count (count sources)}})
  (let [start-time (System/currentTimeMillis)
        state (create-etl-state workflow-id sources)
        pipeline (run-pipeline logger sources)
        failure-stage (:failure-stage pipeline)]
    (if failure-stage
      (let [stage-entry (get-in pipeline [:results failure-stage])]
        (stage-failure logger
                       workflow-id
                       start-time
                       state
                       stage-entry
                       (if (= :scanning failure-stage)
                         {:findings (get-in stage-entry [:stage/result :findings] [])
                          :stats {:high-risk-findings
                                  (high-risk-findings
                                   (get-in stage-entry [:stage/result :findings] []))}}
                         {})))
      (let [duration (- (System/currentTimeMillis) start-time)
            validated-packs (stage-output (:results pipeline) :validation :validated)
            final-stats (build-success-stats sources pipeline)]
        (events/emit-etl-completed logger workflow-id duration final-stats)
        (schema/success :packs validated-packs
                        {:stats final-stats
                         :duration-ms duration})))))
