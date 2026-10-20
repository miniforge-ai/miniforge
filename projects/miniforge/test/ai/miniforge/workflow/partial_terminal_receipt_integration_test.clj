;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.partial-terminal-receipt-integration-test
  (:require [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.phase-opsv.terminal-test-support :as terminal]
            [ai.miniforge.workflow.phase-event-test-support :as journal]
            [ai.miniforge.workflow.runner-events :as publishers]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} verify-journal [completed ids stream options]
  (let [receipt (publishers/publish-phase-completed! stream completed :opsv/actuate (:phase completed))
        history (events/get-events stream)]
    (is (= :workflow/phase-completed (:event/type receipt)))
    (is (= :failure (:phase/outcome receipt)))
    (is (= ids (:phase/artifacts receipt)))
    (is (= [receipt] history))
    (events/close-current-event-stream! stream)
    (is (= history (journal/reopened-events options)))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-partial-publication [kind expected-count ready directory calls]
  (let [configured (assoc-in ready [:execution/opts :opsv/pr-execution :provider :run-command]
                             (partial terminal/unsuccessful-command calls))
        completed (terminal/run-with-publication-failure configured kind)
        result (get-in completed [:phase :result])
        failure (:output result)
        retained (get-in failure [:anomaly/data :opsv/phase-output])
        publication (get-in failure [:anomaly/data :opsv/evidence-failure])
        ids (get-in publication [:anomaly/data :opsv/phase-output :opsv/phase-produced-artifact-refs])]
    (is (= :error (:status result)))
    (is (= expected-count (count ids)))
    (is (= :actuation (get-in (artifact/read-published directory (first ids))
                              [:artifact/metadata :opsv/material-kind])))
    (is (= ids (:opsv/phase-produced-artifact-refs retained) (:artifacts result)))
    (is (= (:opsv/phase-failure retained) (select-keys failure [:anomaly/type :anomaly/message])))
    (is (not= (:anomaly/message publication) (:anomaly/message failure)))
    (is (= 2 (count @calls)))
    (journal/with-stream (partial verify-journal completed ids))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} partial-terminal-publication-retains-provider-error-and-acknowledged-artifacts
  (terminal/with-ready (partial verify-partial-publication :effect-transactions 1)))

(deftest ^{:stratum 2} refused-terminal-event-retains-provider-error-and-acknowledged-artifacts
  (terminal/with-ready (partial verify-partial-publication :event 3)))
