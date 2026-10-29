;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow-resume.checkpoint-read-failure-test
  "Resume restores what a damaged checkpoint still holds and says what it
   could not read, instead of discarding the checkpoint without a word."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.workflow.interface.checkpoints :as workflow-checkpoints]
            [ai.miniforge.workflow-resume.core :as core]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} write-checkpoint!
  "A failed run's checkpoint, written as the store lays it out: a
   snapshot, a manifest and one file per completed phase. Returns the
   files' paths."
  [root run-id]
  (let [dir (fs/path root (str run-id))
        path (fn [& parts] (str (apply fs/path dir parts)))
        files {:snapshot (path "machine-snapshot.edn")
               :manifest (path "manifest.edn")
               :plan (path "phases" "plan.edn")
               :implement (path "phases" "implement.edn")}]
    (fs/create-dirs (fs/path dir "phases"))
    (spit (:snapshot files)
          (pr-str {:execution/id run-id
                   :execution/workflow-id :canonical-sdlc
                   :execution/workflow-version "1.0.0"
                   :execution/status :failed
                   :execution/fsm-state {:_state :failed}
                   :execution/response-chain {}
                   :execution/errors []
                   :execution/artifacts []
                   :execution/metrics {}}))
    (spit (:manifest files)
          (pr-str {:workflow/id run-id
                   :workflow/workflow-id :canonical-sdlc
                   :workflow/workflow-version "1.0.0"
                   :workflow/phases-completed [:plan :implement]
                   :workflow/machine-snapshot-path (:snapshot files)
                   :workflow/phase-checkpoints (select-keys files [:plan :implement])
                   :workflow/last-checkpoint-at "2026-10-28T00:00:00Z"}))
    (doseq [phase [:plan :implement]]
      (spit (get files phase) (pr-str {:workflow/phase phase
                                       :phase/result {:status :completed}})))
    files))

(defn- ^{:stratum 0} reconstruct-under
  "Reconstruct `run-id` with no events, reading checkpoints from `root`."
  [root run-id]
  (let [load-checkpoint-data workflow-checkpoints/load-checkpoint-data]
    (with-redefs [workflow-checkpoints/load-checkpoint-data
                  (fn [workflow-run-id]
                    (load-checkpoint-data workflow-run-id {:checkpoint/root (str root)}))]
      (core/reconstruct-context (str (fs/path root "no-events")) (str run-id)))))

(defn- ^{:stratum 0} failed-paths [failures]
  (mapv #(get-in % [:anomaly/data :checkpoint/path]) failures))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} a-truncated-phase-file-keeps-the-rest-and-is-reported
  (fs/with-temp-dir [root {:prefix "resume-checkpoint-read-"}]
    (let [run-id (random-uuid)
          files (write-checkpoint! root run-id)
          _ (spit (:implement files) "{:phase/result {:status")
          ctx (reconstruct-under root run-id)]
      (testing "the snapshot and the readable phase are restored"
        (is (not (anomaly/anomaly? ctx)))
        (is (= run-id (get-in ctx [:machine-snapshot :execution/id])))
        (is (= [:plan] (:completed-phases ctx))))
      (testing "the unreadable phase file is named for the user"
        (is (= [(:implement files)] (failed-paths (:checkpoint-read-failures ctx))))))))

(deftest ^{:stratum 1} an-unreadable-snapshot-is-the-error-not-missing-events
  (fs/with-temp-dir [root {:prefix "resume-checkpoint-read-"}]
    (let [run-id (random-uuid)
          files (write-checkpoint! root run-id)
          _ (spit (:snapshot files) "{:execution/id")
          result (reconstruct-under root run-id)]
      (is (anomaly/anomaly? result))
      (is (= :unavailable (:anomaly/type result)))
      (is (= [(:snapshot files)]
             (failed-paths (get-in result [:anomaly/data :checkpoint/read-failures])))))))

(deftest ^{:stratum 1} an-empty-snapshot-is-the-error-not-missing-events
  (fs/with-temp-dir [root {:prefix "resume-checkpoint-read-"}]
    (let [run-id (random-uuid)
          files (write-checkpoint! root run-id)
          _ (spit (:snapshot files) "")
          result (reconstruct-under root run-id)]
      (is (anomaly/anomaly? result))
      (is (= :unavailable (:anomaly/type result)))
      (is (= [(:snapshot files)]
             (failed-paths (get-in result [:anomaly/data :checkpoint/read-failures])))))))

(comment ::checkpoint-read-failure)
