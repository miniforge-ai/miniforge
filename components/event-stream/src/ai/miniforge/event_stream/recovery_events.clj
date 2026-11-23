;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.recovery-events
  "N3 recovery facts; construction is not publication or acknowledgment."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.messages :as messages]
            [ai.miniforge.event-stream.workflow-envelope :as envelope]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private located-write-version
  "N3 minor payload version introducing paired storage identity without changing scope."
  "1.1.0")

(defn ^{:stratum 0} checkpoint-location [root run-directory]
  {:checkpoint/root root
   :checkpoint/run-directory run-directory})

(defn- ^{:stratum 0} failure-fields [failure]
  (select-keys failure [:error/message :failure/class]))

(defn- ^{:stratum 0} create [event-type stream workflow-id payload]
  (let [draft (envelope/create stream event-type workflow-id
                               (messages/t event-type payload) {})]
    (if (anomaly/any-anomaly? draft) draft (merge payload draft))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} with-location [draft location]
  (cond
    (anomaly/any-anomaly? draft) draft
    (nil? location) draft
    (string? location) (assoc draft :checkpoint/root location)
    :else (assoc draft :checkpoint/location location :event/version located-write-version)))

(defn ^{:stratum 1} checkpoint-write-failed [stream workflow-id phase failure]
  (create :workflow/checkpoint-write-failed stream workflow-id
          (assoc (failure-fields failure) :phase/name phase)))

(defn ^{:stratum 1} machine-snapshot-write-failed [stream workflow-id state failure]
  (create :workflow/machine-snapshot-write-failed stream workflow-id
          (assoc (failure-fields failure) :machine/state state)))

(defn ^{:stratum 1} resumed [stream workflow-id state phase skipping]
  (create :workflow/resumed stream workflow-id
          {:from-state state :from-phase phase :skipping skipping}))

(defn ^{:stratum 1} spec-hash-mismatch [stream workflow-id expected actual]
  (create :workflow/spec-hash-mismatch stream workflow-id
          {:expected-hash expected :actual-hash actual}))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} checkpoint-written
  ([stream workflow-id phase path size]
   (checkpoint-written stream workflow-id phase path size nil))
  ([stream workflow-id phase path size location]
   (with-location
     (create :workflow/checkpoint-written stream workflow-id
             {:phase/name phase :checkpoint/path path :checkpoint/size-bytes size})
     location)))

(defn ^{:stratum 2} machine-snapshot-written
  ([stream workflow-id state path size]
   (machine-snapshot-written stream workflow-id state path size nil))
  ([stream workflow-id state path size location]
   (with-location
     (create :workflow/machine-snapshot-written stream workflow-id
             {:machine/state state :snapshot/path path :snapshot/size-bytes size})
     location)))

(comment ::checkpoint-write-profiles)
