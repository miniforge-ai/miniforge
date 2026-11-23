;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.lock-protocol-test
  (:require [ai.miniforge.file-durability.boundary.lock-protocol :as protocol]
            [ai.miniforge.file-durability.boundary.probe-deadline :as deadline]
            [ai.miniforge.file-durability.publication-support :as support]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} budget-ms
  "Existing fixture markers need no scheduling-dependent sleep."
  1000)

(def ^{:stratum 0 :private true} poll-ms
  "A test-only poll bound, never an active production default."
  1)

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-arrived! [root]
  (protocol/signal! root :arrived)
  (is (true? (protocol/await? root :arrived (deadline/after-ms budget-ms) poll-ms))))

(defn- ^{:stratum 1} verify-expired! [root]
  (protocol/signal! root :arrived)
  (is (false? (protocol/await? root :absent (System/nanoTime) poll-ms)))
  (is (false? (protocol/await? root :arrived (System/nanoTime) poll-ms))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} test-published-marker-is-observed-before-waiting
  (support/with-root verify-arrived!))

(deftest ^{:stratum 2} test-expired-marker-wait-returns-false-not-nil
  (support/with-root verify-expired!))

(comment (clojure.test/run-tests 'ai.miniforge.file-durability.lock-protocol-test))
