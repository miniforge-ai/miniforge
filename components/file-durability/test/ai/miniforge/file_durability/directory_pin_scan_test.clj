;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.directory-pin-scan-test
  (:require [ai.miniforge.file-durability.boundary.directory-pin-scan :as scan]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} budget-ns
  "A one-second ceiling keeps finite, in-memory scans away from incidental expiry."
  1000000000)

(defn- ^{:stratum 0} entries [names] (mapv fs/path names))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} until [] (+ (System/nanoTime) budget-ns))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} test-witness-must-be-visible-before-entry-fuel-is-exhausted
  (let [stream (entries ["first" "witness"])
        name (fs/path "witness")]
    (is (false? (scan/contains-witness? stream name 1 (until))))
    (is (true? (scan/contains-witness? stream name 2 (until))))
    (is (false? (scan/contains-witness? stream (fs/path "missing") 2 (until))))))

(deftest ^{:stratum 2} test-expired-or-cancelled-scan-cannot-confirm-a-witness
  (let [name (fs/path "witness")
        stream [name]]
    (is (false? (scan/contains-witness? stream name 1 (System/nanoTime))))
    (try
      (.interrupt (Thread/currentThread))
      (let [found? (scan/contains-witness? stream name 1 (until))
            interrupted? (Thread/interrupted)]
        (is (false? found?))
        (is interrupted?))
      (finally (Thread/interrupted)))))

(comment (clojure.test/run-tests 'ai.miniforge.file-durability.directory-pin-scan-test))
