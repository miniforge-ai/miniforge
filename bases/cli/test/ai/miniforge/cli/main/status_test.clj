;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.cli.main.status-test
  (:require [ai.miniforge.cli.main.status.history :as history]
            [ai.miniforge.cli.main.status.presentation :as presentation]
            [ai.miniforge.cli.main.status.summary :as summary]
            [ai.miniforge.cli.app-config :as app-config]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.workflow-resume.interface :as resume]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} context [flag]
  (assoc {:completed? false :failed? false :dag-paused? false :event-count 0} flag true))

(defn- ^{:stratum 0} row [workflow-id at]
  {:workflow-id workflow-id :last-updated at :status :running
   :event-count 0 :completed-phases [] :completed-dag-task-count 0})

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} read-fixture [workflow-id]
  (case workflow-id
    "old" (row workflow-id "2026-09-01T00:00:00Z")
    "new" (row workflow-id "2026-10-01T00:00:00Z")
    (throw (IllegalStateException. "unreadable fixture"))))

(deftest ^{:stratum 1} terminal-statuses-do-not-require-a-last-event
  (doseq [[flag expected] [[:completed? :completed] [:failed? :failed] [:dag-paused? :paused]]]
    (with-redefs [events/read-workflow-events-by-id (constantly [])
                  resume/reconstruct-context (constantly (context flag))]
      (let [result (summary/read-workflow "test-workflow")]
        (is (= expected (:status result)))
        (is (nil? (:last-updated result)))
        (is (zero? (:completed-dag-task-count result)))))))

(deftest ^{:stratum 1} presentation-handles-empty-and-partial-summaries
  (is (.contains (with-out-str (presentation/print-all [])) "none"))
  (is (.contains (with-out-str (presentation/print-workflow (row "test" nil))) "test")))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} discovery-is-empty-on-first-run-and-skips-unreadable-workflows
  (with-redefs [app-config/events-dir (constantly "unused-fixture-directory")
                fs/exists? (constantly false)]
    (is (empty? (history/newest-first))))
  (with-redefs [app-config/events-dir (constantly "unused-fixture-directory")
                fs/exists? (constantly true)
                fs/list-dir (constantly ["old" "broken" "new"])
                fs/directory? (constantly true)
                fs/file-name identity
                summary/read-workflow read-fixture]
    (is (= ["new" "old"] (mapv :workflow-id (history/newest-first))))))

(comment
  (clojure.test/run-tests 'ai.miniforge.cli.main.status-test))
