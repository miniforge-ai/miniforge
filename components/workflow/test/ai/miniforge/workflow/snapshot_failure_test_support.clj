;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.snapshot-failure-test-support
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.workflow.context :as context]
            [ai.miniforge.workflow.fsm :as fsm]
            [babashka.fs :as fs]
            [clojure.test :refer [is]]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} pending-context [root stream logger]
  (let [workflow {:workflow/id :snapshot-failure
                  :workflow/version "1.0.0"
                  :workflow/pipeline [{:phase :plan}]}
        options {:checkpoint/root root
                 :event-stream stream
                 :logger logger}
        ctx (context/create-context workflow {} options)
        initial (fsm/initialize-execution (:execution/fsm-machine ctx))]
    (context/sync-machine-projections (assoc ctx :execution/fsm-state initial))))

(defn ^{:stratum 0} resource-failure []
  (anomaly/exception-anomaly :unavailable "write failed" {:phase/name nil}
                             (java.io.IOException. "Rate limit exceeded")))

(defn ^{:stratum 0} warning-count [entries]
  (count (filter #(= :warn (:log/level %)) @entries)))

(defn ^{:stratum 0} with-journal [operation root]
  (let [directory (str (fs/real-path (fs/create-dirs (fs/path root "journal"))))
        stream (events/create-current-event-stream {:journal-directory directory})]
    (is (events/current-event-stream? stream) (pr-str stream))
    (when (events/current-event-stream? stream)
      (try+ (operation root stream)
        (finally (events/close-current-event-stream! stream))))))

(comment ::authoritative-pending-snapshot-fixtures)
