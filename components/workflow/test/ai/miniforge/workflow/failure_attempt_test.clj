;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.failure-attempt-test
  (:require [ai.miniforge.phase.interface :as phase]
            [ai.miniforge.workflow.checkpoint-store :as checkpoints]
            [ai.miniforge.workflow.checkpoint-test-support :as fixture]
            [ai.miniforge.workflow.context :as context]
            [ai.miniforge.workflow.execution-lifecycle :as lifecycle]
            [ai.miniforge.workflow.runner-failure-event :as facts]
            [ai.miniforge.workflow.runner-failure-source :as source]
            [ai.miniforge.workflow.workflow-failure-test-support :as support]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} native-failure [ctx]
  (let [result (phase/error nil "Failed" "Current failure" {})]
    (assoc ctx :phase {:name :verify :status :failed :result result})))

(defn- ^{:stratum 0} definition []
  {:workflow/id :failure-attempt :workflow/version "1.0.0"
   :workflow/pipeline [{:phase :done}]})

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} a-new-attempt-retains-history-without-reusing-its-cause
  (let [historical (support/failed-context)
        interceptor {:config {:phase :verify} :enter native-failure}
        [failed _] (lifecycle/execute-phase-lifecycle interceptor historical)
        data (facts/data failed)]
    (is (= (:execution/errors historical) (:execution/errors failed)))
    (is (= 1 (:execution/phase-error-offset failed)))
    (is (nil? (source/execution-error failed)))
    (is (= "Current failure" (get-in data [:error :message])))
    (is (= :failure.class/unknown (get-in data [:options :failure/class])))
    (is (= {:message "Later failure"}
           (source/execution-error (update failed :execution/errors conj {:message "Later failure"}))))))

(defn- ^{:stratum 1} verify-checkpoint [root]
  (let [workflow (definition)
        opts {:checkpoint/root root}
        ctx (assoc (context/create-context workflow {} opts)
                   :execution/errors [{:message "Historical failure"}]
                   :execution/phase-error-offset 1)]
    (checkpoints/persist-execution-state! ctx)
    (let [saved (checkpoints/load-checkpoint-data (:execution/id ctx) opts)
          snapshot (:machine-snapshot saved)
          resumed (context/restore-context workflow {} snapshot {} opts)
          rewound (context/restore-context workflow {} snapshot {}
                                           (assoc opts :resume-reset-terminal? true))]
      (is (= 1 (:execution/phase-error-offset snapshot) (:execution/phase-error-offset resumed)))
      (is (= (:execution/errors ctx) (:execution/errors resumed)))
      (is (nil? (source/execution-error resumed)))
      (is (not (contains? rewound :execution/phase-error-offset)))
      (is (empty? (:execution/errors rewound))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} failure-attempt-boundary-survives-resume-but-not-rewind
  (fixture/call-with-temp-checkpoint-root verify-checkpoint))
