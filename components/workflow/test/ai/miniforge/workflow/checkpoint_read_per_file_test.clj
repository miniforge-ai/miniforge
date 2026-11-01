;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.checkpoint-read-per-file-test
  "Checkpoint files load one at a time: an unreadable phase file costs
   that phase and is named; an unreadable snapshot is an anomaly naming
   it, not an empty answer."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.workflow.checkpoint-store :as store]
            [ai.miniforge.workflow.checkpoint-test-support :as fixture]
            [ai.miniforge.workflow.context :as context]
            [ai.miniforge.workflow.interface :as workflow]
            [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} checkpoint-two-phases!
  "Checkpoint a run through :plan and then :implement under `root`.
   Returns the run id."
  [root]
  (let [planned (-> (context/create-context {:workflow/id :per-file-read
                                             :workflow/version "1.0.0"
                                             :workflow/pipeline [{:phase :plan} {:phase :implement}]}
                                            {:task "Test"}
                                            {:checkpoint/root root})
                    (assoc :execution/current-phase :plan
                           :execution/phase-results {:plan {:status :completed}}))
        implemented (-> planned
                        (assoc :execution/current-phase :implement)
                        (assoc-in [:execution/phase-results :implement] {:status :completed}))]
    (store/persist-execution-state! planned)
    (store/persist-execution-state! implemented)
    (:execution/id planned)))

(defn- ^{:stratum 0} failed-paths [failures]
  (mapv #(get-in % [:anomaly/data :checkpoint/path]) failures))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} truncated-phase-file-costs-only-that-phase
  (fixture/call-with-temp-checkpoint-root
   (fn [root]
     (let [run-id (checkpoint-two-phases! root)
           opts {:checkpoint/root root}
           implement-path (:implement (fixture/recorded-paths root run-id))
           _ (spit implement-path "{:phase/result {:status")
           loaded (workflow/load-checkpoint-data run-id opts)]
       (testing "the snapshot and the readable phase still load"
         (is (= run-id (get-in loaded [:machine-snapshot :execution/id])))
         (is (= {:status :completed} (get-in loaded [:phase-results :plan]))))
       (testing "the unreadable phase is left out and named"
         (is (not (contains? (:phase-results loaded) :implement)))
         (is (every? anomaly/anomaly? (:checkpoint/read-failures loaded)))
         (is (= [implement-path] (failed-paths (:checkpoint/read-failures loaded))))
         (is (= :implement (get-in (first (:checkpoint/read-failures loaded))
                                   [:anomaly/data :phase/name]))))))))

(deftest ^{:stratum 1} empty-checkpoint-files-are-read-failures-not-absent
  (fixture/call-with-temp-checkpoint-root
   (fn [root]
     (let [run-id (checkpoint-two-phases! root)
           opts {:checkpoint/root root}
           {implement-path :implement snapshot-path :snapshot} (fixture/recorded-paths root run-id)]
       (testing "a whitespace-only phase file is named, not dropped"
         (spit implement-path "  \n")
         (is (= [implement-path]
                (failed-paths (:checkpoint/read-failures (workflow/load-checkpoint-data run-id opts))))))
       (testing "a zero-byte snapshot is an unreadable checkpoint, not no checkpoint"
         (spit snapshot-path "")
         (let [loaded (workflow/load-checkpoint-data run-id opts)]
           (is (anomaly/anomaly? loaded))
           (is (some #{snapshot-path}
                     (failed-paths (get-in loaded [:anomaly/data :checkpoint/read-failures]))))))))))

(deftest ^{:stratum 1} unreadable-snapshot-is-an-anomaly-naming-it
  (fixture/call-with-temp-checkpoint-root
   (fn [root]
     (let [run-id (checkpoint-two-phases! root)
           opts {:checkpoint/root root}
           snapshot-path (:snapshot (fixture/recorded-paths root run-id))
           _ (spit snapshot-path "{:execution/id")
           loaded (workflow/load-checkpoint-data run-id opts)]
       (is (anomaly/anomaly? loaded))
       (is (= :unavailable (:anomaly/type loaded)))
       (is (= [snapshot-path] (failed-paths (get-in loaded [:anomaly/data :checkpoint/read-failures]))))
       (is (true? (workflow/checkpoint-present? run-id opts)))))))

(comment ::checkpoint-read-per-file)
