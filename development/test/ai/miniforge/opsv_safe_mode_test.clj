;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.opsv-safe-mode-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.cli.workflow-runner.opsv-control :as host]
            [ai.miniforge.event-stream.interface.stream :as events]
            [ai.miniforge.opsv-actuation.interface :as actuation]
            [ai.miniforge.phase-opsv.interface :as opsv]
            [ai.miniforge.phase-opsv.pr-fixtures :as f]
            [ai.miniforge.reliability.interface :as reliability]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} safe-mode-stops-registered-runs-even-if-event-delivery-throws-test
  (let [ctx (host/context (events/create-event-stream {:sinks []}))
        supervisor (:opsv/supervisor ctx)
        manager (:degradation-manager ctx)
        aborted (atom 0)
        run (host/register! supervisor (random-uuid) "/tmp/no-issued-grants"
                             #(do (swap! aborted inc) true))]
    (with-redefs [events/publish! (fn [& _] (throw (ex-info "event storage unavailable" {})))]
      (is (thrown? Exception (reliability/enter-safe-mode! manager :emergency-stop "stop"))))
    (is (= :safe-mode (reliability/degradation-mode manager)))
    (is (:stopped? (actuation/mutation-status (:fence run))))
    (is (= 1 @aborted))
    (is (:cleanup-confirmed? (reliability/safe-mode-stop-result manager)))
    (reliability/exit-safe-mode! manager "recovered" "operator")
    (is (anomaly/anomaly? (host/register! supervisor (random-uuid) "/tmp/no-grants" (constantly true))))))

(deftest ^{:stratum 0} host-safe-mode-during-preflight-prevents-post-test
  (let [host-ctx (host/context (events/create-event-stream {:sinks []}))
        manager (:degradation-manager host-ctx)
        {:keys [ctx runtime calls]} (f/setup)
        handles (host/register! (:opsv/supervisor host-ctx) (:execution/id ctx)
                                (:authority-directory runtime) (constantly true))
        command (fn [options arguments]
                  (let [response (f/command calls options arguments)]
                    (reliability/enter-safe-mode! manager :manual "stop during GET")
                    response))]
    (try
      (let [output (opsv/actuate (assoc-in ctx [:execution/opts :opsv/pr-execution]
                                         (assoc-in (merge runtime handles) [:provider :run-command] command)))]
        (is (anomaly/anomaly? output))
        (is (= :failed (get-in output [:anomaly/data :effect/transaction :effect/state])))
        (is (= ["GET"] (mapv #(get-in % [:arguments 6]) @calls)))
        (is (:cleanup-confirmed? (reliability/safe-mode-stop-result manager))))
      (finally (doseq [file (reverse (file-seq (io/file (get-in runtime [:provider :directory]))))]
                 (io/delete-file file))))))
