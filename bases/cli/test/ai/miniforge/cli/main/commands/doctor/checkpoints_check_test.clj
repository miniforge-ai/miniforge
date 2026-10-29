;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.cli.main.commands.doctor.checkpoints-check-test
  "`mf doctor` reports the checkpoint root and recommends
   `workflow gc-checkpoints` once runs are older than the GC's retention."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.cli.main.commands.doctor :as doctor]
            [ai.miniforge.cli.main.commands.doctor.checks :as sut]
            [ai.miniforge.workflow.interface :as workflow]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private usage
  {:checkpoint/root "/home/u/.miniforge/checkpoints" :runs 12 :older 0
   :oldest-days 9 :max-age-days 30})

(deftest ^{:stratum 0} an-unreadable-root-is-an-optional-failed-check
  (let [result (sut/checkpoints-check
                (anomaly/anomaly :unavailable "Could not read the checkpoint root /x: denied" {}))]
    (is (false? (:check/ok? result)))
    (is (false? (:check/required? result)) "the doctor still passes")
    (is (str/includes? (:check/detail result) "denied"))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} recent-checkpoints-pass-with-a-summary
  (let [result (sut/checkpoints-check usage)]
    (is (true? (:check/ok? result)))
    (is (false? (:check/required? result)))
    (is (str/includes? (:check/detail result) "12 run(s)"))
    (is (str/includes? (:check/detail result) "oldest 9 days"))
    (is (= [] (:check/details result)))))

(deftest ^{:stratum 1} old-checkpoints-recommend-the-gc-command
  (let [result (sut/checkpoints-check (assoc usage :older 5 :oldest-days 120))]
    (is (false? (:check/ok? result)))
    (is (false? (:check/required? result)) "a warning, never a failed doctor")
    (is (str/includes? (first (:check/details result)) "workflow gc-checkpoints"))
    (is (str/includes? (first (:check/details result)) "5 unchanged for over 30 days"))))

(deftest ^{:stratum 1} no-checkpoints-say-so
  (is (str/includes? (:check/detail (sut/checkpoints-check (assoc usage :runs 0 :oldest-days nil)))
                     "none under")))

(deftest ^{:stratum 1} the-doctor-runs-the-check
  (with-redefs [workflow/checkpoint-usage (constantly usage)
                sut/command-check (fn [spec] (sut/finish-check spec {:ok? true :detail "ok"}))]
    (is (some #(= :checkpoints (:check/id %)) (doctor/run-checks {})))))

(comment ::checkpoints-check)
