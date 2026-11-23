;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.terminal-boundary-integration-test
  (:require [ai.miniforge.event-stream.interface :as stream]
            [ai.miniforge.phase-opsv.artifact-test-support :as f]
            [ai.miniforge.phase-opsv.events :as events]
            [ai.miniforge.phase-opsv.interface :as opsv]
            [ai.miniforge.phase-opsv.pr-outcome :as outcome]
            [ai.miniforge.phase-opsv.terminal-test-support :as terminal]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} reject-projection [_ctx _output]
  (throw (ex-info "terminal projection failed" {})))

(defn- ^{:stratum 0} spoof-events [retain output failure]
  (assoc-in (retain output failure) [:anomaly/data :opsv/phase-output :opsv/actuation-events] []))

(defn- ^{:stratum 0} unknown-context [ready calls]
  (assoc-in ready [:execution/opts :opsv/pr-execution :provider :run-command]
            (partial terminal/unsuccessful-command calls)))

(defn- ^{:stratum 0} assert-preflight-retry [ready _directory calls]
  (let [invalid (assoc-in ready [:execution/opts :opsv/evidence-base] nil)
        rejected (f/run-last-phase invalid)
        corrected (assoc rejected :execution/opts (:execution/opts ready))]
    (is (= :error (get-in rejected [:phase :result :status])))
    (is (not (contains? (:execution/input rejected) :opsv/terminal-snapshot)))
    (is (empty? @calls))
    (is (= :success (get-in (f/run-last-phase corrected) [:phase :result :status])))
    (is (= 2 (count @calls)))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} assert-projection-failure [ready _directory calls]
  (let [completed (with-redefs [events/retain-actuation-events reject-projection]
                    (f/run-last-phase (unknown-context ready calls)))
        output (get-in completed [:phase :result :output :anomaly/data :opsv/phase-output])
        recovered (opsv/recover-actuation-evidence!
                   (update completed :execution/opts dissoc :opsv/pr-execution))]
    (is (= :error (get-in completed [:phase :result :status])))
    (is (= :unknown-outcome (get-in output [:opsv/effect-transactions 0 :effect/state])))
    (is (string? (get-in completed [:execution/input :opsv/terminal-snapshot])))
    (f/assert-blocked-transform completed)
    (is (= :error (get-in recovered [:phase :result :status])))
    (is (false? (get-in recovered [:phase :result :output :anomaly/data :opsv/phase-output
                                  :opsv/evidence-bundle :evidence/outcome :outcome/success])))
    (is (= 2 (count @calls)))))

(defn- ^{:stratum 1} assert-untrusted-batch [ready _directory calls]
  (let [spoof (partial spoof-events outcome/retain-failure)
        completed (with-redefs [outcome/retain-failure spoof]
                    (f/run-last-phase (unknown-context ready calls)))
        output (get-in completed [:phase :result :output :anomaly/data :opsv/phase-output])]
    (is (= :error (get-in completed [:phase :result :status])))
    (is (seq (:opsv/actuation-events output)))
    (is (= 1 (count (filter #(= :opsv.actuation/emitted (:event/type %))
                           (stream/get-events (:event-stream completed))))))
    (is (= 2 (count @calls)))))

(deftest ^{:stratum 1} preflight-rejection-does-not-create-a-terminal-actuation
  (terminal/with-ready assert-preflight-retry))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} terminal-projection-failure-retains-the-provider-outcome
  (terminal/with-ready assert-projection-failure))

(deftest ^{:stratum 2} untrusted-empty-event-batch-cannot-suppress-terminal-events
  (terminal/with-ready assert-untrusted-batch))

(comment
  (clojure.test/run-tests 'ai.miniforge.phase-opsv.terminal-boundary-integration-test))
