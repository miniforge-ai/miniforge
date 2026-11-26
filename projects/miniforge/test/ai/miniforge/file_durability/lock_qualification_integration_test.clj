;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.lock-qualification-integration-test
  "Uses real installed runtimes on a disposable path, never production authority."
  (:require [ai.miniforge.file-durability.interface :as durability]
            [ai.miniforge.file-durability.publication-support :as support]
            [ai.miniforge.file-durability.runtime-peer-fixtures :as fixtures]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} verify-locks! [root]
  (is (support/posix? root))
  (when (support/posix? root)
    (let [peer (fixtures/require-peer!)
          directory (fs/real-path root)]
      (is (some? peer))
      (is (= :runtime-locks-verified (durability/verify-runtime-locks! (fs/file directory) peer fixtures/limits)))
      (is (empty? (fs/list-dir root))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1 :integration true} test-independent-runtime-verifies-lock-exclusion-and-reacquisition
  (support/with-root verify-locks!))

(comment (clojure.test/run-tests 'ai.miniforge.file-durability.lock-qualification-integration-test))
