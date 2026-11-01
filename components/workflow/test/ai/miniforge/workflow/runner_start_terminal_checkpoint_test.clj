;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.runner-start-terminal-checkpoint-test
  "The start and terminal checkpoints report a failed write like any
   other, and the start checkpoint is announced after :workflow/started,
   so a run that dies in its first phase still names its checkpoint root."
  (:require [ai.miniforge.event-stream.interface :as es]
            [ai.miniforge.logging.interface :as log]
            [ai.miniforge.workflow.checkpoint-test-support :as fixture]
            [ai.miniforge.workflow.context :as context]
            [ai.miniforge.workflow.runner-start :as runner-start]
            [ai.miniforge.workflow.runner-terminal :as runner-terminal]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} start-context [root stream logger]
  (context/create-context {:workflow/id :start-terminal-checkpoint :workflow/version "1.0.0"
                           :workflow/pipeline [{:phase :plan}]}
                          {}
                          {:checkpoint/root root :event-stream stream :logger logger
                           :workflow/intent {:intent/operation :verify}}))

(defn- ^{:stratum 0} with-journal [f]
  (fs/with-temp-dir [dir {:prefix "start-terminal-"}]
    (let [stream (es/create-current-event-stream
                  {:journal-directory (str (fs/canonicalize (fs/create-dirs (fs/path dir "journal"))))})]
      (try+ (f dir stream)
            (finally (es/close-current-event-stream! stream))))))

(defn- ^{:stratum 0} warned? [entries]
  (boolean (some #(and (= :warn (:log/level %))
                       (= :workflow/checkpoint-write-failed (:log/event %)))
                 @entries)))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} the-start-checkpoint-is-announced-after-started
  (with-journal
    (fn [dir stream]
      (let [root (str (fs/create-dirs (fs/path dir "checkpoints")))
            [logger _] (log/collecting-logger {})
            ctx (start-context root stream logger)
            admitted (runner-start/admit! stream ctx false)
            types (mapv :event/type (es/get-events stream))]
        (is (= :running (:execution/status admitted)))
        (is (= [:workflow/started :workflow/machine-snapshot-written] types))
        (is (= (:snapshot (fixture/recorded-paths root (:execution/id ctx)))
               (:snapshot/path (second (es/get-events stream)))))))))

(deftest ^{:stratum 1} a-failed-start-checkpoint-warns-and-the-run-starts
  (with-journal
    (fn [dir stream]
      (let [[logger entries] (log/collecting-logger {})
            ctx (start-context (fixture/unwritable-checkpoint-root (str dir)) stream logger)
            admitted (runner-start/admit! stream ctx false)]
        (testing "the run is admitted"
          (is (= :running (:execution/status admitted)))
          (is (= :workflow/started (:event/type (first (es/get-events stream))))))
        (testing "the failed write is reported, after started"
          (is (warned? entries))
          (is (= [:workflow/started :workflow/checkpoint-write-failed]
                 (mapv :event/type (es/get-events stream)))))))))

(deftest ^{:stratum 1} a-failed-terminal-checkpoint-warns
  (with-journal
    (fn [dir stream]
      (let [[logger entries] (log/collecting-logger {})
            ctx (start-context (fixture/unwritable-checkpoint-root (str dir)) stream logger)
            finalized (runner-terminal/finalize! stream ctx true (volatile! nil))]
        (is (= (:execution/id ctx) (:execution/id finalized)))
        (is (warned? entries))))))

(comment ::runner-start-terminal-checkpoint)
