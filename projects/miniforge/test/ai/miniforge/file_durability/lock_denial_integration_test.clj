;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.lock-denial-integration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.file-durability.boundary.lock-peer :as peer]
            [ai.miniforge.file-durability.boundary.lock-probe :as lock]
            [ai.miniforge.file-durability.boundary.lock-protocol :as protocol]
            [ai.miniforge.file-durability.boundary.native-process-exit :as exit]
            [ai.miniforge.file-durability.interface :as durability]
            [ai.miniforge.file-durability.lock-denial-fixtures :as denial]
            [ai.miniforge.file-durability.probe-resource-fixtures :as cleanup]
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

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-denial! [mode root]
  (is (support/posix? root))
  (when (support/posix? root)
    (let [observed (volatile! nil)
          waits (atom [])
          launch (partial denial/launch! mode observed)
          limits (assoc fixtures/limits :lock/timeout-ms handshake-budget-ms)
          overrides {#'peer/launch! launch
                     cleanup/delete-scratch (partial denial/delete-after-death! observed @cleanup/delete-scratch)
                     #'protocol/await? (partial denial/record-wait! waits protocol/await?)}]
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
        overrides {#'peer/launch! (partial denial/launch! :timeout observed)
                   cleanup/delete-scratch (partial denial/delete-after-death! observed @cleanup/delete-scratch)
                   (ns-resolve 'ai.miniforge.file-durability.boundary.lock-protocol 'pause!) interrupted-native-wait!}]
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

(defn- ^{:stratum 1} verify-unconfirmed-exit! [interrupted? root]
  (let [observed (volatile! nil)
        limits (assoc fixtures/limits :lock/timeout-ms handshake-budget-ms)
        pause (ns-resolve 'ai.miniforge.file-durability.boundary.lock-protocol 'pause!)
        overrides (cond-> {#'peer/launch! (partial denial/launch! :timeout observed)
                           #'exit/stop! (constantly false)}
                    interrupted? (assoc pause interrupted-native-wait!))]
    (try+
      (let [reply (with-redefs-fn overrides
                    #(durability/verify-runtime-locks! (fs/file (fs/real-path root)) (fixtures/require-peer!) limits))
            flagged? (Thread/interrupted)
            [child scratch] @observed]
        (is (= :anomalies.file-durability/unreaped-peer (anomaly/subtype reply)))
        (is (= interrupted? flagged?))
        (is (= (str scratch) (get-in reply [:anomaly/data :durability/path])))
        (is (.isAlive ^Process (:proc child)))
        (is (fs/exists? scratch)))
      (finally (denial/dispose! observed)))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2 :integration true} test-failed-handshakes-refuse-and-release-their-owned-process-and-scratch
  (doseq [mode (keys denial/scenarios)]
    (support/with-root (partial verify-denial! mode))))

(deftest ^{:stratum 2 :integration true} test-interrupted-native-wait-preserves-cancellation-through-cleanup
  (support/with-root verify-interrupted-wait!))

(deftest ^{:stratum 2 :integration true} test-unconfirmed-exit-never-qualifies-even-after-interruption
  (doseq [interrupted? [false true]]
    (support/with-root (partial verify-unconfirmed-exit! interrupted?))))

(comment (clojure.test/run-tests 'ai.miniforge.file-durability.lock-denial-integration-test))
