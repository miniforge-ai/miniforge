;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.checkpoint-pin-publication-integration-test
  (:require [ai.miniforge.workflow.checkpoint-pin-publication-support :as support]
            [ai.miniforge.workflow.checkpoint-store :as store]
            [ai.miniforge.workflow.checkpoint-store-gc :as gc]
            [ai.miniforge.workflow.checkpoint-test-support :as fixture]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]])
  (:import [java.util.concurrent CountDownLatch]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} verify-held-publication [root]
  (let [id (fixture/expired-run! root)
        opts {:checkpoint/root root}
        inside (support/latch)
        release (support/latch)
        create! (partial support/before-pin-temporary! fs/create-temp-file inside release)]
    (with-redefs [fs/create-temp-file create!]
      (let [pin (future (store/pin-checkpoint! id opts))]
        (is (support/await! inside))
        (let [collect (future (gc/gc-checkpoints! opts))]
          (try
            (is (support/await-contention! root id collect))
            (is (not (realized? collect)))
            (finally (.countDown ^CountDownLatch release)))
          (is (string? (deref pin support/wait-ms ::timed-out)))
          (let [result (deref collect support/wait-ms ::timed-out)]
            (is (empty? (:deleted result)))
            (is (= [id] (get-in result [:kept :pinned])))
            (is (fs/exists? (fs/path root (str id) "pinned")))))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} gc-waits-for-pin-publication-and-keeps-the-run
  (fixture/call-with-temp-checkpoint-root verify-held-publication))

(comment ::pin-gc-publication)
