;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.pr-emission-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.opsv-actuation.interface :as actuation]
            [ai.miniforge.opsv.interface :as opsv]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.pr-fixtures :as fixture]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} runtime-issues-and-executes-a-correlated-pr-test
  (let [{:keys [ctx runtime calls]} (fixture/setup)
        boundary actuation/at-mutation-boundary!
        admissions (atom 0)
        output (with-redefs [actuation/at-mutation-boundary!
                            (fn [f op] (swap! admissions inc) (boundary f op))]
                 (phase/actuate ctx))
        record (:opsv/actuation-record output)
        transaction (first (:opsv/effect-transactions output))
        issued (when transaction (grant/current (:authority-directory runtime) (:effect/grant-id transaction)))]
    (is (not (anomaly/anomaly? output)) (pr-str output))
    (is (= 2 @admissions) "One run admission and one combined authority/POST admission")
    (is (= record (opsv/validate-actuation record)))
    (is (= :pr-only (:effective-actuation-mode record)))
    (is (= :succeeded (:effect/state transaction)))
    (is (= :granted (:effect/authority transaction)))
    (is (= (get-in runtime [:target :opsv/policy-hash])
           (get-in transaction [:effect/proposal :opsv/policy-hash])))
    (is (= (:grant/id issued) (:effect/grant-id transaction)))
    (is (= (get-in output [:opsv/decision-envelope :envelope/id]) (:effect/envelope-id transaction)))
    (is (= ["https://github.com/example/opsv/pull/17"] (:pr-refs record)))
    (is (= ["GET" "POST"] (mapv #(nth (:arguments %) 6) @calls)))
    (is (= transaction (effect/read-record (:effects-directory runtime) (:effect/id transaction))))))

(comment
  (phase/actuate (:ctx (fixture/setup))))
