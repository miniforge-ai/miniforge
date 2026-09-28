;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.opsv-provider-test
  "Durable coordination and provider reconciliation acceptance."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.opsv-actuation.interface :as actuation]
            [ai.miniforge.opsv-provider-fixtures :as fixture]
            [ai.miniforge.opsv-provider-github.interface :as provider]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} durable-unknown-outcome-reconciles-without-a-second-post-test
  (let [{:keys [root effects authority decision grant payload]} (fixture/setup!)
        proposed (actuation/propose-pr! effects fixture/candidate (:grant/id grant) decision fixture/now)
        calls (atom [])
        runtime {:directory root :hostname "github.com"
                 :run-command (partial fixture/simulated-github calls payload)}
        committed (actuation/commit-pr! effects authority (:effect/id fixture/candidate)
                                        (constantly fixture/now) (partial provider/create-pr! runtime))
        reconciled (effect/reconcile! effects committed (partial fixture/observe runtime payload) fixture/now)]
    (is (not (anomaly/anomaly? grant)))
    (is (= :proposed (:effect/state proposed)))
    (is (= :unknown-outcome (:effect/state committed)))
    (is (= :reconciled (:effect/state reconciled)))
    (is (true? (:effect/matched? reconciled)))
    (is (= 17 (get-in reconciled [:effect/observed :pr/number])))
    (is (= ["GET" "POST" "GET"] (mapv #(nth % 6) @calls)))
    (is (= (:effect/grant-id proposed) (:effect/grant-id reconciled)))
    (is (= (:effect/envelope-id proposed) (:effect/envelope-id reconciled)))))

(comment
  (fixture/setup!))
