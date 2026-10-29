;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase.interface.gate-test
  (:require [ai.miniforge.phase.interface.gate :as gate]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} gate-failed-reads-denial-and-unacknowledged-decisions
  (is (gate/gate-failed? {:status :completed :phase/status :failed}))
  (is (gate/gate-failed? {:status :completed :phase/decision-unacknowledged {:anomaly/type :unavailable}}))
  (is (not (gate/gate-failed? {:status :failed})) "an agent failure is not a gate failure")
  (is (not (gate/gate-failed? {:status :completed :phase/decision-envelope {:envelope/decision :allow}})))
  (is (not (gate/gate-failed? nil))))

(comment
  (clojure.test/run-tests 'ai.miniforge.phase.interface.gate-test))
