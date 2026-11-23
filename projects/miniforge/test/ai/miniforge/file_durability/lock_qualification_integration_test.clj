;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.lock-qualification-integration-test
  "Uses real installed runtimes on a disposable path, never production authority."
  (:require [ai.miniforge.file-durability.boundary.lock-probe :as lock]
            [ai.miniforge.file-durability.publication-support :as support]
            [ai.miniforge.file-durability.runtime-peer-fixtures :as fixtures]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} verify-locks! [classpath root]
  (when (support/posix? root)
    (let [peer (fixtures/peer-config classpath)
          directory (fs/real-path root)]
      (is (some? peer))
      (is (true? (lock/check! directory peer)))
      (is (empty? (fs/list-dir root))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1 :integration true} test-independent-runtime-verifies-lock-exclusion-and-reacquisition
  (let [classpath (fixtures/peer-classpath)]
    (when classpath
      (support/with-root (partial verify-locks! classpath)))))

(comment (clojure.test/run-tests 'ai.miniforge.file-durability.lock-qualification-integration-test))
