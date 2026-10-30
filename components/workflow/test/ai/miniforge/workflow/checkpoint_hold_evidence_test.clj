;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.checkpoint-hold-evidence-test
  "The checkpoint retention hold finds a run's checkpoint root from the
   newest write its events announce, a snapshot-only write included, and
   stops reading at that event."
  (:require [ai.miniforge.event-stream.interface :as es]
            [ai.miniforge.logging.interface :as log]
            [ai.miniforge.workflow.checkpoint-store :as store]
            [ai.miniforge.workflow.checkpoint-store-gc :as gc]
            [ai.miniforge.workflow.context :as context]
            [ai.miniforge.workflow.monitoring :as monitoring]
            [ai.miniforge.workflow.runner :as runner]
            [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} archive-event!
  "Write `event` as archived event file number `n` of run `id`."
  [events-dir id n event]
  (let [file (io/file events-dir "archived" (str id) (format "%016x__%012d.transit.json" n n))]
    (io/make-parents file)
    (spit file (if (string? event) event (es/serialize-event event)))))

(defn- ^{:stratum 0} legacy-stream [] (es/create-event-stream {:sinks []}))

(defn- ^{:stratum 0} halted [_runtime _state]
  {:status :halt :halt-reason "test halt" :halting-agent :progress-monitor :checks []})

(defn- ^{:stratum 0} snapshot-only-run!
  "Checkpoint a run under `root` with no phase result yet. Returns its id."
  [root]
  (let [ctx (context/create-context {:workflow/id :hold-evidence :workflow/version "1.0.0"
                                     :workflow/pipeline [{:phase :plan}]}
                                    {:task "Test"} {:checkpoint/root root})]
    (store/persist-execution-state! ctx)
    (:execution/id ctx)))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} a-snapshot-only-iteration-is-announced
  (fs/with-temp-dir [dir {:prefix "hold-evidence-"}]
    (let [stream (es/create-current-event-stream
                  {:journal-directory (str (fs/canonicalize (fs/create-dirs (fs/path dir "journal"))))})
          root (str (fs/create-dirs (fs/path dir "checkpoints")))
          [logger _] (log/collecting-logger {})]
      (try+
        (let [ctx (context/create-context {:workflow/id :hold-evidence :workflow/version "1.0.0"
                                           :workflow/pipeline [{:phase :plan}]}
                                          {:task "Test"}
                                          {:checkpoint/root root :event-stream stream :logger logger})
              _ (with-redefs [monitoring/check-workflow-supervision halted]
                  (runner/execute-single-iteration [] ctx {} 1 (atom {:paused false :stopped false})))
              written (filter #(= :workflow/machine-snapshot-written (:event/type %))
                              (es/get-events stream))]
          (is (= 1 (count written)))
        (is (= (str (fs/path (fs/real-path root) (str (:execution/id ctx)) "machine-snapshot.edn"))
                 (:snapshot/path (first written)))))
        (finally (es/close-current-event-stream! stream))))))

(deftest ^{:stratum 1} a-snapshot-only-checkpoint-under-another-root-holds
  (fs/with-temp-dir [alternate {:prefix "hold-alternate-"}]
    (fs/with-temp-dir [events-dir {:prefix "hold-events-"}]
      (let [id (snapshot-only-run! (str alternate))
            path (str (fs/path alternate (str id) "machine-snapshot.edn"))]
        (archive-event! (fs/file events-dir) id 1
                        (es/machine-snapshot-written (legacy-stream) id :running path 1 (str alternate)))
        (is (true? (es/retention-hold ::gc/checkpoint (str id) (fs/file events-dir))))))))

(deftest ^{:stratum 1} the-scan-stops-at-the-newest-announced-write
  (fs/with-temp-dir [alternate {:prefix "hold-alternate-"}]
    (fs/with-temp-dir [events-dir {:prefix "hold-events-"}]
      (let [id (random-uuid)
            gone (str (fs/path alternate (str id) "phases" "plan.edn"))
            reads (atom 0)
            read-event-file es/read-event-file]
        ;; Older history: one unreadable file and many ordinary events.
        (archive-event! (fs/file events-dir) id 1 "not transit")
        (doseq [n (range 2 40)]
          (archive-event! (fs/file events-dir) id n (es/workflow-resumed (legacy-stream) id :x :plan [])))
        ;; Newest: the write, under a root whose checkpoint is already gone.
        (archive-event! (fs/file events-dir) id 40
                        (es/checkpoint-written (legacy-stream) id :plan gone 1 (str alternate)))
        (with-redefs [es/read-event-file (fn [f] (swap! reads inc) (read-event-file f))]
          (testing "the newest write answers: its checkpoint is gone, so nothing holds"
            (is (false? (es/retention-hold ::gc/checkpoint (str id) (fs/file events-dir)))))
          (testing "only that event was read"
            (is (= 1 @reads))))))))

(comment ::checkpoint-hold-evidence)
