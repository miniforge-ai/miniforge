;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.checkpoint-resolved-root-test
  "One checkpoint write uses one resolved root: retargeting the root's
   alias in the middle of the write cannot split the phase, snapshot and
   manifest across directories."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.workflow.boundary.checkpoint-file :as checkpoint-file]
            [ai.miniforge.workflow.checkpoint-store :as store]
            [ai.miniforge.workflow.checkpoint-test-support :as fixture]
            [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} retarget-after-first-write
  "A `checkpoint-file/write!` that points `alias` at `replacement` once the
   first file (the phase checkpoint) is published."
  [write! alias replacement]
  (let [written (atom 0)]
    (fn [& args]
      (let [path (apply write! args)]
        (when (= 1 (swap! written inc))
          (fs/delete alias)
          (fs/create-sym-link alias replacement))
        path))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-one-root [root]
  (let [first-root (fs/create-dirs (fs/path root "first"))
        second-root (fs/create-dirs (fs/path root "second"))
        alias (fs/create-sym-link (fs/path root "alias") first-root)
        ctx (fixture/plan-completed-context (str alias) {})
        id (str (:execution/id ctx))
        written (with-redefs [checkpoint-file/write! (retarget-after-first-write
                                                      checkpoint-file/write! alias second-root)]
                  (store/persist-execution-state! ctx))
        resolved (str (fs/real-path first-root))
        manifest (edn/read-string (slurp (:checkpoint/manifest-path written)))]
    (is (not (anomaly/anomaly? written)))
    (testing "every file lands under the root resolved before the write"
      (is (= resolved (:checkpoint/root written)))
      (doseq [path [(:checkpoint/phase-path written)
                    (:checkpoint/machine-snapshot-path written)
                    (:checkpoint/manifest-path written)]]
        (is (fs/starts-with? path resolved) path))
      (is (not (fs/exists? (fs/path second-root id)))))
    (testing "the manifest names the files where they were published"
      (is (= (:checkpoint/machine-snapshot-path written)
             (:workflow/machine-snapshot-path manifest)))
      (is (= (:checkpoint/phase-path written)
             (get-in manifest [:workflow/phase-checkpoints :plan]))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} a-retargeted-root-alias-cannot-split-one-checkpoint
  (fixture/call-with-posix-checkpoint-root verify-one-root))

(comment
  (clojure.test/run-tests 'ai.miniforge.workflow.checkpoint-resolved-root-test))
