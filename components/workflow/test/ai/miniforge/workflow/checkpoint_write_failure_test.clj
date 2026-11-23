;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.checkpoint-write-failure-test
  "A checkpoint read or write that fails comes back from the store as an
   anomaly naming the run and phase — never nil, never a throw."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.workflow.checkpoint-store :as store]
            [ai.miniforge.workflow.checkpoint-store-paths :as paths]
            [ai.miniforge.workflow.checkpoint-test-support :as fixture]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} failed-write-returns-an-anomaly-naming-the-run-and-phase
  (fs/with-temp-dir [dir {:prefix "checkpoint-write-failure-"}]
    (let [ctx (fixture/plan-completed-context (fixture/unwritable-checkpoint-root dir) {})
          written (store/persist-execution-state! ctx)]
      (is (anomaly/anomaly? written))
      (is (= :unavailable (:anomaly/type written)))
      (is (= (:execution/id ctx) (get-in written [:anomaly/data :workflow/run-id])))
      (is (= :plan (get-in written [:anomaly/data :phase/name])))
      (is (string? (get-in written [:anomaly/data :anomaly/ex-class]))))))

(deftest ^{:stratum 0} unreadable-manifest-is-a-write-failure-not-a-throw
  (fixture/call-with-temp-checkpoint-root
   (fn [root]
     (let [ctx (fixture/plan-completed-context root {})
           manifest (paths/manifest-path root (:execution/id ctx))]
       (fs/create-dirs (fs/parent manifest))
       (spit manifest "{:workflow/id")
       (is (anomaly/anomaly? (store/persist-execution-state! ctx)))))))

(deftest ^{:stratum 0} invalid-existing-manifest-is-not-saved-over
  (fixture/call-with-temp-checkpoint-root
   (fn [root]
     (let [ctx (fixture/plan-completed-context root {})
           run-id (:execution/id ctx)
           manifest (paths/manifest-path root run-id)
           ;; Parses, but fails the manifest schema once merged into the
           ;; new one: a phase path that is not a string.
           invalid (pr-str {:workflow/phase-checkpoints {:review 42}})]
       (fs/create-dirs (fs/parent manifest))
       (spit manifest invalid)
       (let [written (store/persist-execution-state! ctx)]
         (is (anomaly/anomaly? written))
         (is (= :plan (get-in written [:anomaly/data :phase/name]))))
       (is (= invalid (slurp manifest)))
       (is (not (fs/exists? (paths/machine-snapshot-path root run-id))))))))

(comment ::checkpoint-write-failure)
