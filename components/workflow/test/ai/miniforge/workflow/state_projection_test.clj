;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.state-projection-test
  (:require [ai.miniforge.workflow.fsm :as fsm]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} empty-pipeline-pause-and-resume-project-authoritative-status
  (let [machine (fsm/compile-execution-machine
                  {:workflow/id :empty-projection :workflow/pipeline []})
        running (fsm/start-execution machine (fsm/initialize-execution machine))
        paused (fsm/transition-execution machine running :pause)
        resumed (fsm/transition-execution machine paused :resume)]
    (is (= :paused (fsm/current-state paused)))
    (is (= :paused (:execution/status (fsm/execution-projection machine paused))))
    (is (nil? (fsm/current-phase-id machine paused)))
    (is (= :running (fsm/current-state resumed)))
    (is (= :running (:execution/status (fsm/execution-projection machine resumed))))))

(comment ::empty-pipeline-status)
