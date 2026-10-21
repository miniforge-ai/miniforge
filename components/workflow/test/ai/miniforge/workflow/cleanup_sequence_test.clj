;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.cleanup-sequence-test
  (:require [ai.miniforge.workflow.cleanup-sequence-fixtures :as fixture]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} every-stage-runs-after-a-fatal-failure
  (doseq [stage [:observe :synthesize :promote :capsule :worktree]]
    (let [calls (atom []) failure (Error. "fixture fatal cleanup")]
      (is (identical? failure (fixture/execute calls {stage failure})))
      (is (= [:observe :synthesize :promote :capsule :worktree] @calls)))))

(deftest ^{:stratum 0} ordinary-environment-failure-does-not-mask-later-fatal
  (let [calls (atom []) ordinary (Exception. "fixture release failure")
        fatal (Error. "fixture fatal release")]
    (is (identical? fatal (fixture/execute calls {:capsule ordinary :worktree fatal})))
    (is (= [ordinary] (vec (.getSuppressed fatal))))
    (is (= [:observe :synthesize :promote :capsule :worktree] @calls))))

(deftest ^{:stratum 0} interrupted-observation-releases-resources-before-propagation
  (let [calls (atom []) interrupted (InterruptedException. "fixture cleanup interruption")]
    (try+
      (let [result (fixture/execute calls {:observe interrupted})
            flagged? (.isInterrupted (Thread/currentThread))]
        (is (identical? interrupted result))
        (is flagged?)
        (is (= [:observe :synthesize :promote :capsule :worktree] @calls)))
      (finally (Thread/interrupted)))))

(comment ::cleanup-sequence-regressions)
