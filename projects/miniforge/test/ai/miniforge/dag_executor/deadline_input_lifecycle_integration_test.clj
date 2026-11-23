;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.dag-executor.deadline-input-lifecycle-integration-test
  (:require [ai.miniforge.dag-executor.deadline-process-fixtures :as fixtures]
            [ai.miniforge.dag-executor.protocols.impl.runtime.deadline :as deadline]
            [ai.miniforge.dag-executor.protocols.impl.runtime.deadline-command :as command]
            [ai.miniforge.dag-executor.protocols.impl.runtime.deadline-input :as input]
            [ai.miniforge.dag-executor.protocols.impl.runtime.process :as process]
            [ai.miniforge.subprocess.interface :as subprocess]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} command-budget-ms
  "Keep lifecycle fault injection independent of incidental expiry."
  5000)

(defn- ^{:stratum 0} reject-writer! [& _]
  (throw (IllegalStateException.)))

(defn- ^{:stratum 0} interrupt-after-start! [start! child payload]
  (let [writer (start! child payload)]
    (.interrupt (Thread/currentThread))
    writer))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1 :integration true} test-input-setup-failure-cleans-up-process-and-acquired-readers
  (let [child (atom nil)
        handles (atom [])
        cancelled (atom [])
        start! (comp (partial reset! child) @#'command/start!)
        drain! (partial fixtures/record-drain! handles)
        cancel! (partial fixtures/record-cancel! cancelled)
        overrides {#'command/start! start!
                   #'input/start! reject-writer!
                   #'process/drain-stream drain!
                   #'process/cancel-drain! cancel!}]
    (try
      (let [reply (with-redefs-fn overrides
                    #(command/run! (deadline/after-ms command-budget-ms) fixtures/long-lived-command (byte-array 0)))]
        (is (= :command-error (:reason reply)))
        (is (= 2 (count @handles)))
        (is (= @handles @cancelled))
        (is (not (.isAlive ^Process @child))))
      (finally
        (when-let [started @child] (process/destroy-process-tree! started))
        (run! subprocess/cancel-drain! @handles)))))

(deftest ^{:stratum 1 :integration true} test-cancellation-during-input-restores-the-flag-after-process-cleanup
  (let [child (atom nil)
        start! (comp (partial reset! child) @#'command/start!)
        writer! (partial interrupt-after-start! input/start!)
        overrides {#'command/start! start!
                   #'input/start! writer!}]
    (try
      (let [reply (with-redefs-fn overrides
                    #(command/run! (deadline/after-ms command-budget-ms) fixtures/long-lived-command (byte-array 0)))
            interrupted? (Thread/interrupted)]
        (is (= :interrupted (:reason reply)))
        (is interrupted?)
        (is (not (.isAlive ^Process @child))))
      (finally
        (Thread/interrupted)
        (when-let [started @child] (process/destroy-process-tree! started))))))

(comment (clojure.test/run-tests 'ai.miniforge.dag-executor.deadline-input-lifecycle-integration-test))
