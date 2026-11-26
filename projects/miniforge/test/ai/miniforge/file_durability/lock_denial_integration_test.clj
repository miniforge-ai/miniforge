;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.lock-denial-integration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.file-durability.boundary.lock-probe :as lock]
            [ai.miniforge.file-durability.boundary.lock-protocol :as protocol]
            [ai.miniforge.file-durability.boundary.native-process-exit :as exit]
            [ai.miniforge.file-durability.interface :as durability]
            [ai.miniforge.file-durability.lock-denial-fixtures :as denial]
            [ai.miniforge.file-durability.publication-support :as support]
            [ai.miniforge.file-durability.runtime-peer-fixtures :as fixtures]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} handshake-budget-ms
  "Allow native shell scheduling, not the production twenty-second marker wait."
  500)

(defn- ^{:stratum 0} interrupted-native-wait! [_]
  (throw (InterruptedException.)))

(defn- ^{:stratum 0} fail-host-release! [signal! root marker]
  (if (= :host-released marker)
    (throw (java.io.IOException.))
    (signal! root marker)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-denial! [mode root]
  (is (support/posix? root))
  (when (support/posix? root)
    (let [observed (volatile! nil)
          waits (atom [])
          limits (assoc fixtures/limits :lock/timeout-ms handshake-budget-ms)
          overrides (assoc (denial/overrides mode observed)
                            #'protocol/await? (partial denial/record-wait! waits protocol/await?))]
      (try+
        (is (false? (with-redefs-fn overrides
                      #(lock/check! (fs/real-path root) (fixtures/require-peer!) limits))))
        (let [[child scratch] @observed
              ^Process worker (:proc child)]
          (is (seq @waits))
          (is (every? #{(first @waits)} @waits))
          (is (not (.isAlive worker)))
          (is (not (fs/exists? scratch)))
          (is (empty? (fs/list-dir root))))
        (finally (denial/dispose! observed))))))

(defn- ^{:stratum 1} verify-interrupted-wait! [root]
  (let [observed (volatile! nil)
        overrides (assoc (denial/overrides :timeout observed)
                          (ns-resolve 'ai.miniforge.file-durability.boundary.lock-protocol 'pause!) interrupted-native-wait!)]
    (try+
      (let [reply (with-redefs-fn overrides
                    #(lock/check! (fs/real-path root) (fixtures/require-peer!) fixtures/limits))
            interrupted? (Thread/interrupted)
            [child scratch] @observed
            ^Process worker (:proc child)]
        (is (false? reply))
        (is interrupted?)
        (is (not (.isAlive worker)))
        (is (not (fs/exists? scratch)))
        (is (empty? (fs/list-dir root))))
      (finally
        (Thread/interrupted)
        (denial/dispose! observed)))))

(defn- ^{:stratum 1} verify-unconfirmed-exit! [mode root]
  (let [observed (volatile! nil)
        limits (assoc fixtures/limits :lock/timeout-ms handshake-budget-ms)
        pause (ns-resolve 'ai.miniforge.file-durability.boundary.lock-protocol 'pause!)
        peer-mode (if (= :io mode) :false-lock :timeout)
        overrides (cond-> (assoc (denial/overrides peer-mode observed) #'exit/stop! (constantly false))
                    (= :interrupted mode) (assoc pause interrupted-native-wait!)
                    (= :io mode) (assoc #'protocol/signal! (partial fail-host-release! protocol/signal!)))]
    (try+
      (let [reply (with-redefs-fn overrides
                    #(durability/verify-runtime-locks! (fs/file (fs/real-path root)) (fixtures/require-peer!) limits))
            flagged? (Thread/interrupted)
            [child scratch] @observed]
        (is (= :anomalies.file-durability/unreaped-peer (anomaly/subtype reply)))
        (is (= (= :interrupted mode) flagged?))
        (is (= (str scratch) (get-in reply [:anomaly/data :durability/path])))
        (is (.isAlive ^Process (:proc child)))
        (is (fs/exists? scratch))
        (when (= :io mode)
          (is (= :fault (get-in reply [:anomaly/data :durability/primary-failure :anomaly/type])))
          (is (= :native-probe (get-in reply [:anomaly/data :durability/primary-failure :anomaly/data :durability/operation])))))
      (finally (denial/dispose! observed)))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2 :integration true} test-failed-handshakes-refuse-and-release-their-owned-process-and-scratch
  (doseq [mode (keys denial/scenarios)]
    (support/with-root (partial verify-denial! mode))))

(deftest ^{:stratum 2 :integration true} test-interrupted-native-wait-preserves-cancellation-through-cleanup
  (support/with-root verify-interrupted-wait!))

(deftest ^{:stratum 2 :integration true} test-unconfirmed-exit-never-qualifies-even-after-interruption
  (doseq [mode [:timeout :interrupted :io]]
    (support/with-root (partial verify-unconfirmed-exit! mode))))

(comment (clojure.test/run-tests 'ai.miniforge.file-durability.lock-denial-integration-test))
