;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.lock-protocol-test
  (:require [ai.miniforge.file-durability.boundary.lock-protocol :as protocol]
            [ai.miniforge.file-durability.publication-support :as support]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} wait-budget
  "Expire immediately without a real twenty-second wait."
  (ns-resolve 'ai.miniforge.file-durability.boundary.lock-protocol 'wait-ms))

(defn- ^{:stratum 0} verify-arrived! [root]
  (protocol/signal! root :arrived)
  (is (true? (protocol/await? root :arrived))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-expired! [root]
  (with-redefs-fn {wait-budget 0} #(is (false? (protocol/await? root :absent)))))

(deftest ^{:stratum 1} test-published-marker-is-observed-before-waiting
  (support/with-root verify-arrived!))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} test-expired-marker-wait-returns-false-not-nil
  (support/with-root verify-expired!))

(comment (clojure.test/run-tests 'ai.miniforge.file-durability.lock-protocol-test))
