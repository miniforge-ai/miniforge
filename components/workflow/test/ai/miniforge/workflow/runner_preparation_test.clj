;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.runner-preparation-test
  (:require [ai.miniforge.dag-executor.interface :as dag-exec]
            [ai.miniforge.workflow.runner-preparation :as preparation]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} pipeline
  [{:name :a :owned-input-keys #{:a/record}} {:name :b}])

(deftest ^{:stratum 0} the-agent-capsule-exec-runs-in-the-capsule-checkout
  ;; A runner-acquired capsule records the host worktree as :worktree-path;
  ;; the capsule's own checkout is :capsule-workdir (/workspace by default).
  (let [seen (atom nil)]
    (with-redefs [dag-exec/execute! (fn [_executor _env-id _cmd opts]
                                      (reset! seen opts)
                                      {:data {:stdout "" :stderr "" :exit-code 0}})]
      (let [ctx (preparation/build-initial-context
                 {:workflow/id :t :workflow/version "1.0.0" :workflow/pipeline [{:phase :done}]}
                 {:task "x"}
                 {:execution-mode :governed :executor ::capsule :environment-id "env-1"
                  :worktree-path "/host/worktrees/task-1" :capsule-workdir "/workspace"}
                 (constantly nil))]
        ((:execution/exec-fn ctx) "true")
        (is (= "/workspace" (:workdir @seen)))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} a-fresh-run-drops-lifecycle-owned-input
  (is (= {:task "x"} (preparation/admit-input pipeline {:task "x" :a/record {:forged true}} {}))))

(deftest ^{:stratum 1} a-resume-keeps-its-checkpointed-input-whole
  (let [input {:task "x" :a/record {:phase :a}}]
    (is (= input (preparation/admit-input pipeline input {:resume-phase-results {:a {}}})))
    (is (= input (preparation/admit-input pipeline input {:resume-machine-snapshot {}})))))

(comment
  (clojure.test/run-tests 'ai.miniforge.workflow.runner-preparation-test))
