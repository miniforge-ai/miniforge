;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.checkpoint-temporary-test
  (:require [ai.miniforge.workflow.checkpoint-temporary-support :as support]
            [ai.miniforge.workflow.checkpoint-test-support :as fixture]
            [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is]])
  (:import [java.io IOException]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} verify-failed-write [root]
  (let [target (str (fs/path root "snapshot.edn"))
        failure (IOException. "injected partial write")
        fail-write! (partial support/partial-write! spit failure)]
    (support/write! target :original)
    (with-redefs [spit fail-write!]
      (is (identical? failure (support/attempt-write! target :replacement))))
    (is (= :original (edn/read-string (slurp target))))
    (is (= ["snapshot.edn"] (mapv fs/file-name (fs/list-dir root))))))

(defn- ^{:stratum 0} verify-interleaving [root]
  (let [target (str (fs/path (fs/real-path root) "snapshot.edn"))
        paths (atom [])
        interleaved-write! (partial support/interleave! spit (atom false) target paths)]
    (with-redefs [spit interleaved-write!]
      (is (= target (support/attempt-write! target :outer))))
    (is (= :outer (edn/read-string (slurp target))))
    (is (= 2 (count (distinct @paths))))
    (is (= ["snapshot.edn"] (mapv fs/file-name (fs/list-dir root))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} failed-write-cleans-only-its-temporary-file
  (fixture/call-with-temp-checkpoint-root verify-failed-write))

(deftest ^{:stratum 1} overlapping-writes-never-consume-another-writers-temporary-file
  (fixture/call-with-temp-checkpoint-root verify-interleaving))

(comment ::temporary-checkpoint-ownership)
