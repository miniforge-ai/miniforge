;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.cleanup-sequence-test
  (:require [ai.miniforge.workflow.runner-cleanup :as cleanup]
            [ai.miniforge.workflow.runner-environment :as environment]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} acquired []
  {:executor :capsule :environment-id :capsule-id
   :worktree-executor :worktree :worktree-environment-id :worktree-id})

(defn- ^{:stratum 0} record! [calls failures stage & _]
  (swap! calls conj stage)
  (when-let [failure (get failures stage)] (throw failure)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} execute [calls failures]
  (with-redefs [cleanup/observe-workflow-signal! (partial record! calls failures :observe)
                cleanup/synthesize-patterns! (partial record! calls failures :synthesize)
                cleanup/promote-mature-learnings! (partial record! calls failures :promote)
                environment/release-execution-environment! (partial record! calls failures)]
    (try+ (cleanup/post-workflow-cleanup! {} nil {} nil (acquired))
      (catch Object _ (:throwable &throw-context)))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} every-stage-runs-after-a-fatal-failure
  (doseq [stage [:observe :synthesize :promote :capsule :worktree]]
    (let [calls (atom []) failure (Error. "fixture fatal cleanup")]
      (is (identical? failure (execute calls {stage failure})))
      (is (= [:observe :synthesize :promote :capsule :worktree] @calls)))))

(deftest ^{:stratum 2} ordinary-environment-failure-does-not-mask-later-fatal
  (let [calls (atom []) ordinary (Exception. "fixture release failure")
        fatal (Error. "fixture fatal release")]
    (is (identical? fatal (execute calls {:capsule ordinary :worktree fatal})))
    (is (= [ordinary] (vec (.getSuppressed fatal))))
    (is (= [:observe :synthesize :promote :capsule :worktree] @calls))))

(deftest ^{:stratum 2} interrupted-observation-releases-resources-before-propagation
  (let [calls (atom []) interrupted (InterruptedException. "fixture cleanup interruption")]
    (try+
      (let [result (execute calls {:observe interrupted})
            flagged? (.isInterrupted (Thread/currentThread))]
        (is (identical? interrupted result))
        (is flagged?)
        (is (= [:observe :synthesize :promote :capsule :worktree] @calls)))
      (finally (Thread/interrupted)))))

(comment ::cleanup-sequence-regressions)
