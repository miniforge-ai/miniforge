;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.boundary.lock-peer-session
  "Exercise the peer half of an isolated publication and mutual-lock proof."
  (:require [ai.miniforge.file-durability.boundary.lock-protocol :as protocol]
            [ai.miniforge.file-durability.boundary.protected-directory :as directory]
            [ai.miniforge.file-durability.boundary.publication-probe :as publication]
            [clojure.java.io :as io]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} confirm-held! [root acquired?]
  (and acquired?
       (protocol/signal! root :peer-holding)
       (protocol/await? root :host-observed-exclusion)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} exercise-locks! [root]
  (and (protocol/with-attempt root false?)
       (protocol/signal! root :peer-excluded)
       (protocol/await? root :host-released)
       (protocol/with-attempt root (partial confirm-held! root))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} completed? [expected-runtime location]
  (try+
    (let [root (directory/existing! (io/file location))
          runtime (keyword expected-runtime)]
      (and (= runtime (protocol/runtime-kind))
           (= :publication-verified (publication/check! root))
           (exercise-locks! root)))
    (catch InterruptedException _ (.interrupt (Thread/currentThread)) false)
    (catch Object _ false)))

(comment (completed? "unsupported" "."))
