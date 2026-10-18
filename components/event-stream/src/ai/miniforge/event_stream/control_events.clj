;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.control-events
  "Carry supplied control audit facts into the requested event."
  (:require [ai.miniforge.event-stream.core :as core]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private request-fields
  [:action/target :action/justification :action/parameters
   :action/approval :action/pre-state :action/timestamp])

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} requested [stream action]
  (let [workflow-id (get-in action [:action/target :target-id])
        event (core/control-action-requested stream workflow-id (:action/id action)
                                             (:action/type action) (:action/requester action))]
    (merge event (select-keys action request-fields))))
