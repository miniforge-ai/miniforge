;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.terminal-cleanup-test
  (:require [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.workflow.checkpoint-store :as store]
            [ai.miniforge.workflow.checkpoint-test-support :as checkpoints]
            [ai.miniforge.workflow.phase-event-test-support :as journal]
            [ai.miniforge.workflow.phase-test-support :as fixture]
            [ai.miniforge.workflow.runner-cleanup :as cleanup]
            [ai.miniforge.workflow.runner-events :as publishers]
            [ai.miniforge.workflow.workflow-start-test-support :as runner]
            [clojure.test :refer [deftest is use-fixtures]]))

(use-fixtures :each checkpoints/with-temp-checkpoint-root fixture/with-workflow-phase-test-support)

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} observe-cleanup [calls captured original opts ctx workflow exception acquired]
  (reset! captured ctx)
  (swap! calls conj :cleanup)
  (original opts ctx workflow exception acquired))

(defn- ^{:stratum 0} verify-failed-checkpoint [ctx]
  (let [persisted (store/load-checkpoint-data (:execution/id ctx) {})]
    (is (= :failed (get-in persisted [:machine-snapshot :execution/status])))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-fatal-publication [publisher verify-checkpoint stream _options]
  (let [calls (atom [])
        captured (atom nil)
        signals (atom [])
        observe-signal (partial swap! signals conj)
        opts {:observe-signal-fn observe-signal}
        cleanup-fn (partial observe-cleanup calls captured cleanup/post-workflow-cleanup!)]
    (with-redefs [publishers/publish-phase-completed! (constantly nil)]
      (with-redefs-fn {publisher runner/throw-fatal}
        #(is (thrown? AssertionError (runner/execute stream opts calls cleanup-fn)))))
    (let [ctx @captured]
      (is (= [:phase-enter :cleanup] @calls))
      (is (= :failed (:execution/status ctx) (get-in ctx [:execution/output :status])))
      (is (= :workflow-terminal-publication-failed (:type (last (:execution/errors ctx)))))
      (is (= [:workflow-failed] (mapv :signal/type @signals)))
      (verify-checkpoint ctx))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} fatal-initial-publication-keeps-cleanup-failed
  (journal/with-stream (partial verify-fatal-publication #'events/workflow-completed identity)))

(deftest ^{:stratum 2} fatal-failure-publication-keeps-cleanup-and-checkpoint-failed
  (journal/with-stream (partial verify-fatal-publication #'events/workflow-failed
                               verify-failed-checkpoint)))

(comment ::fatal-fallback-cleanup)
