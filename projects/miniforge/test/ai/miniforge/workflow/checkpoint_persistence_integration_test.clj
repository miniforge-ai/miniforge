;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.checkpoint-persistence-integration-test
  (:require [ai.miniforge.workflow.checkpoint-persistence-support :as support]
            [ai.miniforge.workflow.checkpoint-store :as store]
            [ai.miniforge.workflow.checkpoint-store-paths :as paths]
            [ai.miniforge.workflow.checkpoint-test-support :as fixture]
            [ai.miniforge.workflow.interface :as workflow]
            [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} verify-manifest [root]
  (let [id (random-uuid)
        initial-results (support/results {:explore :completed :plan :completed :implement :completed :review :completed})
        resumed-results (support/results {:plan :completed :implement :completed :release :failed})
        initial (support/execution root id [:explore :plan :implement :review] initial-results)
        resumed (support/execution root id [:release] resumed-results)]
    (store/persist-execution-state! initial)
    (store/persist-execution-state! resumed)
    (let [loaded (workflow/load-checkpoint-data id {:checkpoint/root root})]
      (is (= [:explore :plan :implement :review :release]
             (get-in loaded [:manifest :workflow/phases-completed])))
      (is (= (support/phase-result :completed) (get-in loaded [:phase-results :explore])))
      (is (= (support/phase-result :failed) (get-in loaded [:phase-results :release]))))))

(defn- ^{:stratum 0} verify-history [root]
  (let [id (random-uuid)
        base (support/execution root id [:implement :done] {})
        denied (assoc-in base [:execution/phase-results :implement] (support/gate-result :deny))
        allowed (assoc-in base [:execution/phase-results :implement] (support/gate-result :allow))
        directory (paths/workflow-checkpoint-dir root id)]
    (store/persist-execution-state! denied)
    (store/persist-execution-state! allowed)
    (let [history (mapv edn/read-string (str/split-lines (slurp (fs/file directory store/gate-history-filename))))
          loaded (workflow/load-checkpoint-data id {:checkpoint/root root})]
      (is (= 2 (count history)))
      (is (= [:deny :allow] (mapv :decision history)))
      (is (= :stale-references (-> history first :phase/gate-failures first :gate)))
      (is (= "2026-08-29T00:00:00Z" (-> history first :phase/gate-failures first :errors first :timestamp)))
      (is (= :allow (get-in loaded [:phase-results :implement :phase/decision-envelope :envelope/decision]))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} resumed-partial-pipeline-retains-prior-checkpoints
  (fixture/call-with-temp-checkpoint-root verify-manifest))

(deftest ^{:stratum 1} phase-reentry-retains-every-gate-decision
  (fixture/call-with-temp-checkpoint-root verify-history))

(comment ::durable-checkpoint-integration)
