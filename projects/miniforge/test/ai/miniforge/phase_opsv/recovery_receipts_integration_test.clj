;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.recovery-receipts-integration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.phase-opsv.interface :as opsv]
            [ai.miniforge.phase-opsv.terminal-test-support :as terminal]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} refuse-bundle [publish directory record]
  (if (= :evidence-bundle (get-in record [:artifact/metadata :opsv/material-kind]))
    (anomaly/anomaly :unavailable "bundle refused" {})
    (publish directory record)))

(defn- ^{:stratum 0} retained-output [ctx]
  (get-in ctx [:phase :result :output :anomaly/data :opsv/phase-output]))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-retries [ready _directory calls]
  (let [configured (assoc-in ready [:execution/opts :opsv/pr-execution :provider :run-command]
                             (partial terminal/unsuccessful-command calls))
        completed (terminal/run-with-publication-failure configured :evidence-bundle)
        publish (partial refuse-bundle artifact/publish!)
        retries (with-redefs [artifact/publish! publish]
                  (vec (take 2 (rest (iterate opsv/recover-actuation-evidence! completed)))))
        original (retained-output completed)]
    (is (seq (:opsv/phase-produced-artifact-refs original)))
    (doseq [retried retries]
      (is (= :error (get-in retried [:phase :result :status])))
      (is (= [] (get-in retried [:phase :result :artifacts])))
      (is (= [] (:opsv/phase-produced-artifact-refs (retained-output retried))))
      (is (= (dissoc original :opsv/phase-produced-artifact-refs)
             (dissoc (retained-output retried) :opsv/phase-produced-artifact-refs)))
      (is (= (select-keys (get-in completed [:phase :result :output]) [:anomaly/type :anomaly/message])
             (select-keys (get-in retried [:phase :result :output]) [:anomaly/type :anomaly/message]))))
    (is (= 2 (count @calls)))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} consecutive-bundle-refusals-do-not-report-prior-material-receipts
  (terminal/with-ready verify-retries))
