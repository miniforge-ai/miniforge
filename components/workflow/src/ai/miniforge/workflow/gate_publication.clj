;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.gate-publication
  "A phase decision is usable only after its audit event is acknowledged."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.workflow.gate-receipt :as receipt]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} nonfatal? [value]
  (not (instance? Error value)))

(defn- ^{:stratum 0} publish-decision! [stream ctx phase-result envelope]
  (let [workflow-id (or (:execution/id ctx) (:workflow/id ctx) (:workflow-id ctx))
        phase (or (:phase/name phase-result) (:name phase-result) (:phase phase-result))
        event (events/phase-decision stream workflow-id phase envelope)]
    (if (anomaly/any-anomaly? event) event
      (receipt/acknowledged event (events/publish! stream event)))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} call-with-exception-handling [operation]
  (try+ (operation)
        (catch anomaly/any-anomaly? result result)
        (catch InterruptedException _
          (let [result (receipt/failure :interrupted)]
            (.interrupt (Thread/currentThread))
            result))
        (catch nonfatal? _ (receipt/failure :exception))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} emit! [ctx phase-result envelope]
  (if-let [stream (or (:event-stream ctx) (:execution/event-stream ctx)
                     (get-in ctx [:execution/opts :event-stream]))]
    (call-with-exception-handling (partial publish-decision! stream ctx phase-result envelope))
    (receipt/failure :missing-stream)))

(comment
  (emit! {} {} nil))
