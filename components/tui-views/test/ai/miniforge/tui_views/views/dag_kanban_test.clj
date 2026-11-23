;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under Apache 2.0.
(ns ai.miniforge.tui-views.views.dag-kanban-test
  (:require
   [ai.miniforge.tui-views.views.dag-kanban :as sut]
   [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} a-cancelled-workflow-is-a-done-card
  (let [columns (sut/group-tasks-by-status [{:name "stopped" :status :cancelled}
                                            {:name "broke" :status :failed}])
        done-cards (:cards (last columns))]
    (is (= [{:label "broke" :status :failed} {:label "stopped" :status :cancelled}]
           done-cards)
        "a cancelled run is finished, so it is a done card, not dropped from every column")))

(comment ::dag-kanban)
