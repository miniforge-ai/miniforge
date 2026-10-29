;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.runner-checkpoint-test
  "After a failed checkpoint write the runner warns, publishes
   `:workflow/checkpoint-write-failed`, and keeps going."
  (:require [ai.miniforge.event-stream.interface :as es]
            [ai.miniforge.logging.interface :as log]
            [ai.miniforge.workflow.checkpoint-test-support :as fixture]
            [ai.miniforge.workflow.monitoring :as monitoring]
            [ai.miniforge.workflow.runner :as runner]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} halted
  "Supervision that halts at once, so the iteration runs no phase and goes
   straight to its checkpoint."
  [_runtime _state]
  {:status :halt :halt-reason "test halt" :halting-agent :progress-monitor :checks []})

(defn- ^{:stratum 0} journal-stream [dir]
  (es/create-current-event-stream
   {:journal-directory (str (fs/canonicalize (fs/create-dirs (fs/path dir "journal"))))}))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} runner-warns-publishes-and-continues-after-a-failed-write
  (fs/with-temp-dir [dir {:prefix "runner-checkpoint-"}]
    (let [stream (journal-stream dir)
          [logger entries] (log/collecting-logger {:min-level :debug})]
      (try+
        (let [ctx (fixture/plan-completed-context (fixture/unwritable-checkpoint-root dir)
                                                {:event-stream stream :logger logger})
              result (with-redefs [monitoring/check-workflow-supervision halted]
                       (runner/execute-single-iteration
                        [] ctx {} 1 (atom {:paused false :stopped false})))
              failures (filter #(= :workflow/checkpoint-write-failed (:event/type %))
                               (es/get-events stream))]
          (testing "the iteration returns its context instead of throwing"
            (is (= (:execution/id ctx) (:execution/id result))))
          (testing "a :workflow/checkpoint-write-failed event names the phase"
            (is (= 1 (count failures)))
            (is (= :plan (:phase/name (first failures))))
            (is (string? (:error/message (first failures)))))
          (testing "the operator is warned"
            (is (some #(and (= :warn (:log/level %))
                            (= :workflow/checkpoint-write-failed (:log/event %)))
                      @entries))))
        (finally (es/close-current-event-stream! stream))))))

(deftest ^{:stratum 1} failed-write-without-an-event-stream-still-continues
  (fs/with-temp-dir [dir {:prefix "runner-checkpoint-"}]
    (let [[logger entries] (log/collecting-logger {:min-level :debug})
          ctx (fixture/plan-completed-context (fixture/unwritable-checkpoint-root dir)
                                              {:logger logger})
          result (with-redefs [monitoring/check-workflow-supervision halted]
                   (runner/execute-single-iteration
                    [] ctx {} 1 (atom {:paused false :stopped false})))]
      (testing "no stream to publish on: the iteration still returns its context"
        (is (= (:execution/id ctx) (:execution/id result))))
      (testing "the operator is still warned"
        (is (some #(and (= :warn (:log/level %))
                        (= :workflow/checkpoint-write-failed (:log/event %)))
                  @entries))))))

(deftest ^{:stratum 1} a-written-phase-checkpoint-is-announced
  (fs/with-temp-dir [dir {:prefix "runner-checkpoint-"}]
    (let [stream (journal-stream dir)
          root (str (fs/create-dirs (fs/path dir "checkpoints")))]
      (try+
        (let [ctx (fixture/plan-completed-context root {:event-stream stream})
              _ (with-redefs [monitoring/check-workflow-supervision halted]
                  (runner/execute-single-iteration
                   [] ctx {} 1 (atom {:paused false :stopped false})))
              written (filter #(= :workflow/checkpoint-written (:event/type %))
                              (es/get-events stream))]
          (is (= 1 (count written)))
          (is (= :plan (:phase/name (first written))))
          (is (= (str (fs/path root (str (:execution/id ctx)) "phases" "plan.edn"))
                 (:checkpoint/path (first written))))
          (is (pos? (:checkpoint/size-bytes (first written)))))
        (finally (es/close-current-event-stream! stream))))))

(comment ::runner-checkpoint)
