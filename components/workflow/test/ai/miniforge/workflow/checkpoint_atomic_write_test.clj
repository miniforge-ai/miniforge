;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.checkpoint-atomic-write-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.workflow.checkpoint-store :as store]
            [ai.miniforge.workflow.boundary.checkpoint-permissions :as permissions]
            [ai.miniforge.workflow.checkpoint-test-support :as fixture]
            [ai.miniforge.workflow.context :as context]
            [ai.miniforge.workflow.interface :as workflow]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} execution [root]
  (context/create-context
   {:workflow/id :atomic-checkpoint
    :workflow/version "1.0.0"
    :workflow/pipeline [{:phase :plan}]}
   {} {:checkpoint/root root}))

(defn- ^{:stratum 0} discard-prepared-file! [prepare! prepared path target]
  ;; Remove only after preparation succeeds, so Files/move itself must fail.
  (prepare! path target)
  (swap! prepared inc)
  (fs/delete path))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-replacement [root]
  (let [ctx (execution root)
        opts {:checkpoint/root root}
        run-id (:execution/id ctx)
        first-write (store/persist-execution-state! ctx)
        first-read (workflow/load-checkpoint-data run-id opts)
        updated (assoc ctx :execution/redirect-count 1)
        replacement (store/persist-execution-state! updated)
        reopened (workflow/load-checkpoint-data run-id opts)]
    (is (map? first-write))
    (is (= run-id (get-in first-read [:machine-snapshot :execution/id])))
    (is (= first-write replacement))
    (is (= 1 (get-in reopened [:machine-snapshot :execution/redirect-count])))
    (is (empty? (fs/glob root "**/*.tmp")))))

(defn- ^{:stratum 1} verify-failed-replacement [root]
  (let [ctx (execution root)
        opts {:checkpoint/root root}
        run-id (:execution/id ctx)
        persisted (store/persist-execution-state! ctx)
        before (workflow/load-checkpoint-data run-id opts)
        prepared (atom 0)
        discard! (partial discard-prepared-file! permissions/prepare! prepared)]
    (is (map? persisted))
    (with-redefs [permissions/prepare! discard!]
      (is (anomaly/anomaly? (store/persist-execution-state!
                            (assoc ctx :execution/redirect-count 1)))))
    (is (= 1 @prepared))
    (is (= before (workflow/load-checkpoint-data run-id opts)))
    (is (fs/exists? (:checkpoint/machine-snapshot-path persisted)))
    (is (fs/exists? (:checkpoint/manifest-path persisted)))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} first-write-and-replacement-reopen-complete-snapshots
  (fixture/call-with-temp-checkpoint-root verify-replacement))

(deftest ^{:stratum 2} failed-rename-preserves-the-previous-resumable-checkpoint
  (fixture/call-with-temp-checkpoint-root verify-failed-replacement))

(comment ::checkpoint-atomic-write)
