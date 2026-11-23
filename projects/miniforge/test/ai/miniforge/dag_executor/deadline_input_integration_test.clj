;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.dag-executor.deadline-input-integration-test
  (:require [ai.miniforge.dag-executor.protocols.impl.runtime.deadline :as deadline]
            [ai.miniforge.dag-executor.protocols.impl.runtime.deadline-command :as command]
            [ai.miniforge.dag-executor.protocols.impl.runtime.deadline-input :as input]
            [ai.miniforge.dag-executor.protocols.impl.runtime.process :as process]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.charset StandardCharsets]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} fixture-budget-ms
  "Expire unresponsive fixture input without a long test suite delay."
  500)

(def ^{:stratum 0 :private true} success-budget-ms
  "Keep ordinary fixture delivery away from incidental scheduling expiry."
  5000)

(def ^{:stratum 0 :private true} scheduling-ceiling-ms
  "Allow both shared cleanup graces, not the fixture's thirty-second lifetime."
  4000)

(def ^{:stratum 0 :private true} oversized-input
  "Four MiB exceeds the native pipe capacity; a non-reader cannot accept it all."
  (byte-array (* 4 1024 1024)))

(def ^{:stratum 0 :private true} fixture-commands
  "Only disposable local processes; none invokes a provider."
  {:echo ["/bin/sh" "-c" "exec cat"]
   :count ["/bin/sh" "-c" "wc -c"]
   :blocked ["/bin/sh" "-c" "exec sleep 30"]
   :early-exit ["/bin/sh" "-c" "exit 0"]
   :output ["/bin/sh" "-c" "printf complete"]
   :failure ["/bin/sh" "-c" "printf denied >&2; exit 7"]})

(defn- ^{:stratum 0} stalled-writer [& _]
  (future @(promise)))

(defn- ^{:stratum 0} reject-start! [& _]
  (throw (AssertionError.)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} assert-cancelled! [payloads]
  (try
    (.interrupt (Thread/currentThread))
    (let [reply (apply command/run! (deadline/after-ms success-budget-ms) [] payloads)
          interrupted? (Thread/interrupted)]
      (is (= :interrupted (:reason reply)))
      (is interrupted?))
    (finally (Thread/interrupted))))

(deftest ^{:stratum 1 :integration true} test-input-is-delivered-and-closed-before-complete-output
  (doseq [text ["" "first\nsecond\u0000λ"]]
    (let [payload (.getBytes ^String text StandardCharsets/UTF_8)
          reply (command/run! (deadline/after-ms success-budget-ms) (:echo fixture-commands) payload)]
      (is (= 0 (:exit reply)))
      (is (= text (:out reply)))
      (is (= "" (:err reply))))))

(deftest ^{:stratum 1 :integration true} test-blocked-input-shares-the-deadline-and-owned-process-cleanup
  (let [child (atom nil)
        start! (comp (partial reset! child) @#'command/start!)
        started (System/nanoTime)]
    (try
      (let [reply (with-redefs-fn {#'command/start! start!}
                    #(command/run! (deadline/after-ms fixture-budget-ms)
                                   (:blocked fixture-commands) oversized-input))
            elapsed-ms (/ (- (System/nanoTime) started) deadline/nanos-per-ms)]
        (is (= :deadline (:reason reply)))
        (is (= "" (:out reply)))
        (is (< elapsed-ms scheduling-ceiling-ms))
        (is (not (.isAlive ^Process @child))))
      (finally (when-let [started @child] (process/destroy-process-tree! started))))))

(deftest ^{:stratum 1 :integration true} test-large-input-is-complete-across-native-pipe-backpressure
  (let [reply (command/run! (deadline/after-ms success-budget-ms)
                            (:count fixture-commands) oversized-input)]
    (is (= 0 (:exit reply)))
    (is (= (alength ^bytes oversized-input) (Long/parseLong (str/trim (:out reply)))))))

(deftest ^{:stratum 1 :integration true} test-zero-exit-with-unaccepted-input-is-not-success
  (let [reply (command/run! (deadline/after-ms success-budget-ms)
                            (:early-exit fixture-commands) oversized-input)]
    (is (= :input-error (:reason reply)))
    (is (not (zero? (:exit reply))))
    (is (= "" (:out reply)))))

(deftest ^{:stratum 1 :integration true} test-process-exit-does-not-imply-writer-completion
  (let [writer (atom nil)
        start! (comp (partial reset! writer) stalled-writer)]
    (try
      (with-redefs [input/start! start!]
        (let [reply (command/run! (deadline/after-ms fixture-budget-ms)
                                  (:output fixture-commands) oversized-input)]
          (is (= :deadline (:reason reply)))
          (is (= "" (:out reply)))
          (is (future-cancelled? @writer))))
      (finally (when-let [started @writer] (future-cancel started))))))

(deftest ^{:stratum 1 :integration true} test-provider-failure-output-remains-a-failure-with-unfinished-input
  (with-redefs [input/start! stalled-writer]
    (let [reply (command/run! (deadline/after-ms success-budget-ms)
                              (:failure fixture-commands) oversized-input)]
      (is (= 7 (:exit reply)))
      (is (= "denied" (:err reply))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} test-preexisting-cancellation-never-launches-an-input-or-read-only-command
  (with-redefs-fn {#'command/start! reject-start!}
    #(run! assert-cancelled! [[] [oversized-input]])))

(comment (clojure.test/run-tests 'ai.miniforge.dag-executor.deadline-input-integration-test))
