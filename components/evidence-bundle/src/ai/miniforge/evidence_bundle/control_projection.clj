;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.control-projection
  "Project recorded control events without replacing their audit facts."
  (:require [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} action-fields
  [:action/id :action/type :action/timestamp :action/requester
   :action/justification :action/target :action/parameters
   :action/approval :action/result :action/pre-state :action/post-state])

(def ^{:stratum 0} execution-fields
  [:action/approval :action/pre-state :action/post-state])

(defn- ^{:stratum 0} event-entry [event] [(:action/id event) event])

(defn- ^{:stratum 0} recorded-result [executed]
  (let [result (:action/result executed)]
    (cond
      (nil? executed) {:status :pending}
      (and (map? result) (contains? result :status)) result
      (response/error? result) (assoc result :status :failure)
      :else result)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} action-record [executed-by-id requested]
  (let [executed (get executed-by-id (:action/id requested))
        timestamp (get requested :action/timestamp (:event/timestamp requested))
        result (recorded-result executed)]
    (-> (merge (select-keys requested action-fields)
               (select-keys executed execution-fields))
        (assoc :action/timestamp timestamp :action/result result))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} from-events [requested executed]
  (let [executed-by-id (into {} (map event-entry) executed)]
    (mapv (partial action-record executed-by-id) requested)))

(comment
  (from-events [] []))
