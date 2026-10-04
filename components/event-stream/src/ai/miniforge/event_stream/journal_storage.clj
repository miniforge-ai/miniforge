;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.journal-storage
  "Internal durable adapter; the publisher will supply validated drafts and scopes."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.boundary.journal-owner :as owner]
            [ai.miniforge.event-stream.boundary.journal-recovery :as recovery]
            [ai.miniforge.event-stream.commit-journal :as journal]
            [ai.miniforge.event-stream.commit-model :as model]
            [ai.miniforge.event-stream.journal-writer :as writer]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} close! [store]
  (let [state (get-in store [:journal :state])]
    (locking state
      (swap! state assoc :closed? true)
      (owner/release! (:directory store) (:owner store)))))

(defn ^{:stratum 0} commit! [store scope event]
  (let [state (get-in store [:journal :state])]
    (locking state
      (if (:closed? @state)
        (model/failure :unavailable :journal/closed event)
        (journal/commit! (:journal store) scope event)))))

(defn- ^{:stratum 0} recovered-store [directory channel state]
  (let [journal (journal/create state (partial writer/persist! directory))]
    {:directory directory
     :owner channel
     :journal journal}))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} recover-owned! [directory channel]
  (let [transferred? (atom false)]
    (try+
      (let [state (recovery/recover! directory)
            store (if (anomaly/anomaly? state) state (recovered-store directory channel state))]
        (reset! transferred? (not (anomaly/anomaly? store)))
        store)
      (finally (when-not @transferred? (owner/release! directory channel))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} open!
  "Own an existing canonical directory and recover every immutable record.
   The host must exclusively control the directory and its ancestors; advisory
   locks do not authorize writers. Returns a store or anomaly. Close every store."
  [directory]
  (let [channel (owner/acquire! directory)]
    (if (anomaly/anomaly? channel) channel (recover-owned! directory channel))))

(comment
  ::durable-commit-adapter)
