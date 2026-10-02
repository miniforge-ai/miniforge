;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.boundary.commit
  "A storage receipt is authoritative; uncertain writes fence the journal."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.commit-model :as model]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} fence! [journal failure]
  (swap! (:state journal) assoc :failure failure)
  failure)

(defn- ^{:stratum 0} acknowledge! [journal scope event]
  (swap! (:state journal) model/accept scope event)
  event)

(defn- ^{:stratum 0} critical-cause [throwable]
  (loop [cause throwable seen #{}]
    (cond
      (nil? cause) nil
      (contains? seen cause) nil
      (or (instance? Error cause) (instance? InterruptedException cause)) cause
      :else (recur (.getCause ^Throwable cause) (conj seen cause)))))

(defn- ^{:stratum 0} propagate-critical! [cause]
  (when (instance? InterruptedException cause) (.interrupt (Thread/currentThread)))
  (throw cause))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} accept-receipt! [journal scope event receipt]
  (cond
    (anomaly/any-anomaly? receipt) (fence! journal receipt)
    (= event receipt) (acknowledge! journal scope event)
    :else (fence! journal (model/failure :fault :commit/unacknowledged event))))

(defn- ^{:stratum 1} handle-thrown! [journal event object throwable]
  (let [failure (if (anomaly/any-anomaly? object) object
                   (model/failure :fault :commit/write-failed event))
        critical (critical-cause throwable)]
    (fence! journal failure)
    (if critical (propagate-critical! critical) failure)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} persist-with-exception-handling! [journal scope event]
  (swap! (:state journal) assoc :writing? true)
  (try+
    (accept-receipt! journal scope event ((:persist! journal) scope event))
    (catch Object object
      (handle-thrown! journal event object (:throwable &throw-context)))
    (finally (swap! (:state journal) dissoc :writing?))))

(comment
  ::storage-acknowledgment)
