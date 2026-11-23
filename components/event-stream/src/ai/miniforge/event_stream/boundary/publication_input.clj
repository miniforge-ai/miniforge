;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.boundary.publication-input
  "Validate and redact supported current-write drafts without committing or delivering."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.boundary.critical :as critical]
            [ai.miniforge.event-stream.commit-model :as model]
            [ai.miniforge.event-stream.publication-identity :as identity]
            [ai.miniforge.event-stream.publication-input-spec :as spec]
            [ai.miniforge.event-stream.scope-policy :as policy]
            [ai.miniforge.redaction.interface :as redaction]
            [malli.core :as m]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} failure [type code event]
  (let [safe-id (try+
                  (let [event-id (:event/id event)] (when (uuid? event-id) event-id))
                  (catch Object _
                    (when-let [fatal (critical/cause (:throwable &throw-context))]
                      (critical/propagate! fatal))
                    nil))]
    (model/failure type code {:event/id safe-id})))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} caught-failure [event object throwable]
  (let [fatal (critical/cause throwable)]
    (cond
      fatal (critical/propagate! fatal)
      (anomaly/any-anomaly? object) object
      :else (failure :fault :publication/preparation-failed event))))

(defn- ^{:stratum 1} redacted-input [event scope]
  (let [redacted (redaction/redact event)
        before (identity/projection event)
        after (identity/projection redacted)]
    (cond
      (anomaly/any-anomaly? redacted) redacted
      (not (m/validate spec/CurrentDraft redacted)) (failure :invalid-input :publication/invalid-draft event)
      (not (redaction/clean? before)) (failure :invalid-input :publication/identity-redacted event)
      (not= before after) (failure :invalid-input :publication/identity-redacted event)
      (not= scope (policy/scope redacted)) (failure :invalid-input :publication/identity-redacted event)
      :else [scope redacted])))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} prepare
  "Return [authoritative-scope redacted-draft] or an anomaly. This is not a receipt.
   Reject unsupported families and caller positions. Historical records are not inputs."
  [event]
  (try+
    (cond
      (anomaly/any-anomaly? event) event
      (not (redaction/supported? event)) (failure :invalid-input :publication/invalid-draft event)
      (not (m/validate spec/CurrentDraft event)) (failure :invalid-input :publication/invalid-draft event)
      :else (anomaly/let-ok [scope (policy/scope event)] (redacted-input event scope)))
    (catch Object object (caught-failure event object (:throwable &throw-context)))))

(comment
  (prepare {}))
