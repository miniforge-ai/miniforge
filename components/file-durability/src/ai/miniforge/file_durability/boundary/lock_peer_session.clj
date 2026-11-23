;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.boundary.lock-peer-session
  "Exercise the peer half of an isolated publication and mutual-lock proof."
  (:require [ai.miniforge.file-durability.boundary.lock-protocol :as protocol]
            [ai.miniforge.file-durability.boundary.protected-directory :as directory]
            [ai.miniforge.file-durability.boundary.publication-probe :as publication]
            [ai.miniforge.file-durability.boundary.probe-deadline :as deadline]
            [clojure.java.io :as io]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} confirm-held! [root until poll-ms acquired?]
  (and acquired?
       (protocol/signal! root :peer-holding)
       (protocol/await? root :host-observed-exclusion until poll-ms)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} exercise-locks! [root until poll-ms]
  (and (protocol/with-attempt root false?)
       (protocol/signal! root :peer-excluded)
       (protocol/await? root :host-released until poll-ms)
       (protocol/with-attempt root (partial confirm-held! root until poll-ms))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} completed? [expected-runtime location limits]
  (try+
    (and (= expected-runtime (name (protocol/runtime-kind)))
      (let [until (deadline/after-ms (:lock/timeout-ms limits))
            root (directory/existing! (io/file location))]
        (and (= :publication-verified (publication/check! root))
             (deadline/live? until)
             (exercise-locks! root until (:lock/poll-interval-ms limits)))))
    (catch InterruptedException _ (.interrupt (Thread/currentThread)) false)
    (catch Object _ false)))

(comment (completed? "unsupported" "." nil))
