;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.runner-heartbeat-boundary-test
  (:require [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.workflow.runner-events :as runner]
            [ai.miniforge.workflow.runner-lifetime-fixtures :as fixture]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} interrupted-stop-restores-flag-and-propagates
  (let [failure (InterruptedException. "fixture heartbeat interruption")]
    (try+
      (with-redefs [events/stop-heartbeat! (partial fixture/fail! failure)]
        (let [result (try+ (runner/stop-phase-heartbeat! :owned)
                       (catch Object _ (:throwable &throw-context)))
              flagged? (.isInterrupted (Thread/currentThread))]
          (is (identical? failure result))
          (is flagged?)))
      (finally (Thread/interrupted)))))

(deftest ^{:stratum 0} ordinary-legacy-stop-failure-remains-a-warning
  (with-redefs [events/stop-heartbeat! (partial fixture/fail! (Exception. "fixture failure"))]
    (let [result (atom :pending)
          output (with-out-str (reset! result (runner/stop-phase-heartbeat! :owned)))]
      (is (nil? @result))
      (is (seq output)))))

(comment ::runner-heartbeat-critical-policy)
