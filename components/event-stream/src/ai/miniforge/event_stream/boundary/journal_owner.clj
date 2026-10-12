;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.boundary.journal-owner
  "Exclusive directory ownership across processes and within this runtime."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.boundary.journal-files :as files]
            [ai.miniforge.event-stream.commit-model :as model]
            [slingshot.slingshot :refer [try+]])
  (:import [java.nio.channels FileChannel]))

;------------------------------------------------------------------------------ Layer 0

(defonce ^{:private true :stratum 0} owners (atom {}))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} close-channel! [directory ^FileChannel channel]
  (try+
    (.close channel)
    (catch Exception _ (model/failure :unavailable :journal/ownership nil))
    (finally (when-not (.isOpen channel) (swap! owners dissoc directory)))))

(defn- ^{:stratum 1} acquire-unowned! [directory]
  (let [channel (files/lock-channel! directory)]
    (when-not (anomaly/anomaly? channel) (swap! owners assoc directory channel))
    channel))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} release! [directory channel]
  (locking owners
    (when (identical? channel (get @owners directory))
      (close-channel! directory channel))))

(defn ^{:stratum 2} acquire! [directory]
  ;; Do not open a second descriptor for an in-process owner. On some systems,
  ;; closing that descriptor could release the first descriptor's process lock.
  (locking owners
    (try+
      (cond
        (.isInterrupted (Thread/currentThread)) (model/failure :unavailable :commit/interrupted nil)
        (not (files/safe-directory? directory)) (model/failure :invalid-input :journal/ownership nil)
        (contains? @owners directory) (model/failure :conflict :journal/ownership nil)
        :else (acquire-unowned! directory))
      (catch InterruptedException _
        (.interrupt (Thread/currentThread))
        (model/failure :unavailable :commit/interrupted nil))
      (catch Exception _ (model/failure :unavailable :journal/ownership nil)))))

(comment
  ::exclusive-journal-owner)
