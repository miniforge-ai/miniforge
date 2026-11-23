;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.boundary.lock-probe
  "Start a distinct trusted runtime, then verify genuine mutual file-lock exclusion."
  (:require [ai.miniforge.file-durability.boundary.lock-protocol :as protocol]
            [ai.miniforge.file-durability.boundary.lock-peer :as peer]
            [ai.miniforge.file-durability.boundary.probe-deadline :as deadline]
            [ai.miniforge.file-durability.boundary.probe-resources :as resources]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} probe-prefix
  "Each handshake owns a new private directory and cannot reuse stale markers."
  "miniforge-lock-probe-")

(defn- ^{:stratum 0} interrupted-proof []
  (.interrupt (Thread/currentThread))
  false)

(defn- ^{:stratum 0} start-peer! [child root configuration limits until acquired?]
  (when (and acquired? (deadline/live? until))
    (vreset! child (peer/launch! root configuration limits))
    (protocol/await? root :peer-excluded until (:lock/poll-interval-ms limits))))

(defn- ^{:stratum 0} confirm-peer-held! [root child limits until]
  (and (deadline/live? until)
       (protocol/signal! root :host-released)
       (protocol/await? root :peer-holding until (:lock/poll-interval-ms limits))
       (protocol/with-attempt root false?)
       (protocol/signal! root :host-observed-exclusion)
       (= 0 (:exit (deref child (min (:lock/exit-timeout-ms limits) (deadline/remaining-ms until)) nil)))
       (protocol/with-attempt root true?)
       (deadline/live? until)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} prove! [peer limits root child]
  (let [until (deadline/after-ms (:lock/timeout-ms limits))]
    (try+
      (protocol/allocate! root)
      (boolean
       (and (protocol/with-attempt root (partial start-peer! child root peer limits until))
            (confirm-peer-held! root @child limits until)))
      (catch InterruptedException _ (interrupted-proof)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} check! [directory peer limits]
  (resources/call! directory probe-prefix (:lock/exit-timeout-ms limits)
                   (partial prove! peer limits)))

(comment (protocol/runtime-kind))
