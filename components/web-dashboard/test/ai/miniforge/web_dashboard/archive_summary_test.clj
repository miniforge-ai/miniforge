;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under Apache 2.0.
(ns ai.miniforge.web-dashboard.archive-summary-test
  (:require
   [ai.miniforge.web-dashboard.archive-summary :as sut]
   [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} a-cancelled-run-reads-as-cancelled-not-failed
  (is (= :cancelled (sut/derive-status {:event/type :workflow/cancelled}))
      "N3: a cancel is reported by :workflow/cancelled, and is not a failure")
  (is (= :cancelled (sut/derive-status {:event/type :workflow/completed
                                        :workflow/status :cancelled}))
      "an older run that folded the cancel into :workflow/completed")
  (is (= :failed (sut/derive-status {:event/type :workflow/completed :workflow/status :failure})))
  (is (= :completed (sut/derive-status {:event/type :workflow/completed :workflow/status :success})))
  (is (= :stale (sut/derive-status nil)) "no terminal event in the tail"))

(deftest ^{:stratum 0} a-cancelled-terminal-event-ends-the-summary
  (let [workflow-id (random-uuid)
        ended #inst "2026-11-18T10:00:00Z"
        summary (sut/derive-summary {:workflow/id workflow-id :event/type :workflow/started}
                                    [{:event/type :workflow/cancelled :event/timestamp ended}]
                                    nil 0)]
    (is (= :cancelled (:status summary)))
    (is (= ended (:completed-at summary)))))

(comment ::archive-summary)
