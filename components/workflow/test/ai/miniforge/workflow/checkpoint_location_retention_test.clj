;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.checkpoint-location-retention-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.workflow.checkpoint-location-support :as support]
            [ai.miniforge.workflow.checkpoint-store-gc :as gc]
            [ai.miniforge.workflow.checkpoint-test-support :as fixture]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} verify-alias [kind root]
  (let [id (random-uuid)
        layout (support/layout! root id kind)]
    (support/announce! layout id)
    (is (true? (events/retention-hold ::gc/checkpoint (str id) (:events layout))))
    (is (= (:root layout) (gc/run-checkpoint-root (str id) (:events layout))))
    (fs/delete-tree (:directory layout))
    (is (false? (boolean (events/retention-hold ::gc/checkpoint (str id) (:events layout)))))))

(defn- ^{:stratum 0} verify-uncertain [root]
  (let [id (random-uuid)
        layout (support/layout! root id :snapshot)
        event (support/announcement layout id)
        invalid [(assoc event :checkpoint/location nil)
                 (assoc event :checkpoint/location {})
                 (assoc-in event [:checkpoint/location :checkpoint/root] "relative-root")
                 (assoc-in event [:checkpoint/location :checkpoint/run-directory] "relative-run")
                 (assoc event :event/version "1.0.0")
                 (support/announcement layout id "relative-root")
                 (assoc (support/announcement layout id nil)
                        :snapshot/path (str (fs/path "relative-root" (str id) "machine.edn")))
                 (assoc event :event/version "2.0.0")
                 (assoc event :event/version "1.invalid")
                 (assoc event :workflow/id (random-uuid))]]
    (doseq [fact invalid]
      (support/archive! layout id fact)
      (is (true? (events/retention-hold ::gc/checkpoint (str id) (:events layout))))
      (is (anomaly/anomaly? (gc/run-checkpoint-root (str id) (:events layout)))))))

(defn- ^{:stratum 0} verify-legacy [root]
  (let [id (random-uuid)
        layout (support/layout! root id :snapshot)
        logical-path (str (fs/path (:root layout) (str id) "machine.edn"))
        stream (events/create-event-stream {:sinks []})
        event (events/machine-snapshot-written stream id :running logical-path (fs/size logical-path))]
    (support/archive! layout id event)
    (is (true? (events/retention-hold ::gc/checkpoint (str id) (:events layout))))
    (is (= (:root layout) (gc/run-checkpoint-root (str id) (:events layout))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} phase-aliases-do-not-lose-the-checkpoint-root
  (fixture/call-with-posix-checkpoint-root (partial verify-alias :phase)))

(deftest ^{:stratum 1} physical-run-names-do-not-replace-logical-workflow-identity
  (fixture/call-with-posix-checkpoint-root (partial verify-alias :snapshot)))

(deftest ^{:stratum 1} uncertain-announcements-hold-events-without-inventing-a-root
  (fixture/call-with-posix-checkpoint-root verify-uncertain))

(deftest ^{:stratum 1} legacy-logical-path-announcements-remain-supported
  (fixture/call-with-posix-checkpoint-root verify-legacy))

(comment ::retention-with-explicit-physical-identity)
