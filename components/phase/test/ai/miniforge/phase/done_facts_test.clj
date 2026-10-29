;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase.done-facts-test
  (:require [ai.miniforge.phase.interface :as phase]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} complete [ctx]
  ((:enter (phase/get-phase-interceptor {:phase :done})) ctx))

(defn- ^{:stratum 0} prior-phase []
  {:name :previous :status :failed :duration-ms 9000
   :error {:message "Prior failure"} :artifacts [(random-uuid)]
   :phase/transition-request {:transition/type :transition/redirect}
   :result {:status :error :artifacts [(random-uuid)]}
   :metrics {:tokens 100 :cost-usd 1.0 :duration-ms 9000}})

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} done-has-owned-empty-receipts-and-timing
  (let [before (System/currentTimeMillis)
        completed (:phase (complete {}))
        {:keys [started-at ended-at duration-ms metrics result]} completed]
    (is (= :completed (:status completed)))
    (is (= :success (:status result)))
    (is (= [] (:artifacts result)))
    (is (<= before started-at))
    (is (<= ended-at (System/currentTimeMillis)))
    (is (= (max 0 (- ended-at started-at)) duration-ms))
    (is (= {:tokens 0 :cost-usd 0.0 :duration-ms duration-ms} metrics (:metrics result)))))

(deftest ^{:stratum 1} terminal-facts-cannot-inherit-previous-phase-state
  (let [prior (prior-phase)
        ctx {:phase prior :execution/phase-results {:previous prior}}
        completed (complete ctx)]
    (is (= {:previous prior} (:execution/phase-results completed)))
    (is (= :done (get-in completed [:phase :name])))
    (is (= [] (get-in completed [:phase :result :artifacts])))
    (is (= 0 (get-in completed [:phase :metrics :tokens])))
    (doseq [key [:error :artifacts :phase/transition-request]]
      (is (not (contains? (:phase completed) key))))))
