;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.dag-executor.deadline-setup-integration-test
  (:require [ai.miniforge.dag-executor.protocols.impl.runtime.deadline :as deadline]
            [ai.miniforge.dag-executor.protocols.impl.runtime.deadline-command :as command]
            [ai.miniforge.dag-executor.protocols.impl.runtime.process :as process]
            [ai.miniforge.subprocess.interface :as subprocess]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} command-budget-ms
  "A generous deadline keeps this regression on setup failure, not timeout."
  5000)

(def ^{:stratum 0 :private true} long-lived-command
  "A direct child that remains alive unless setup failure cleans it up."
  ["/bin/sh" "-c" "exec sleep 30"])

(defn- ^{:stratum 0} record-drain! [handles stream options]
  (let [handle (subprocess/drain-stream stream options)]
    (swap! handles conj handle)
    handle))

(defn- ^{:stratum 0} record-cancel! [cancelled handle]
  (swap! cancelled conj handle)
  (subprocess/cancel-drain! handle))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} reject-drain! [attempts fail-at handles stream options]
  (if (= fail-at (swap! attempts inc))
    (throw (IllegalStateException.))
    (record-drain! handles stream options)))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2 :integration true} test-reader-setup-failure-cleans-up-every-acquired-resource
  (doseq [fail-at [1 2]]
    (let [started (atom nil)
          handles (atom [])
          cancelled (atom [])
          attempts (atom 0)
          start! (comp (partial reset! started) @#'command/start!)
          drain! (partial reject-drain! attempts fail-at handles)
          cancel! (partial record-cancel! cancelled)]
      (try
        (let [reply (with-redefs-fn {#'command/start! start!
                                    #'process/drain-stream drain!
                                    #'process/cancel-drain! cancel!}
                      #(command/run! (deadline/after-ms command-budget-ms) long-lived-command))]
          (is (= :command-error (:reason reply)))
          (is (not (.isAlive ^Process @started)))
          (is (= (dec fail-at) (count @handles)))
          (is (= @handles @cancelled)))
        (finally
          (process/destroy-process-tree! @started)
          (run! subprocess/cancel-drain! @handles))))))

(comment (clojure.test/run-tests 'ai.miniforge.dag-executor.deadline-setup-integration-test))
