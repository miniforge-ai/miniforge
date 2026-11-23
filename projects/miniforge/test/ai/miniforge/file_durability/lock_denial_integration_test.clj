;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.lock-denial-integration-test
  (:require [ai.miniforge.file-durability.boundary.lock-peer :as peer]
            [ai.miniforge.file-durability.boundary.lock-probe :as lock]
            [ai.miniforge.file-durability.boundary.lock-protocol :as protocol]
            [ai.miniforge.file-durability.lock-denial-fixtures :as denial]
            [ai.miniforge.file-durability.publication-support :as support]
            [ai.miniforge.file-durability.runtime-peer-fixtures :as fixtures]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [try+]])
  (:import [java.util.concurrent TimeUnit]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} handshake-budget-ms
  "Allow native shell scheduling, not the production twenty-second marker wait."
  500)

(def ^{:stratum 0 :private true} cleanup-budget-ms
  "Allow asynchronous OS termination to settle; never the fixture's full lifetime."
  2000)

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
                     #'protocol/await? (partial denial/record-wait! waits protocol/await?)}]
      (try+
        (is (false? (with-redefs-fn overrides
                      #(lock/check! (fs/real-path root) (fixtures/require-peer!) limits))))
        (let [[child scratch] @observed
              ^Process worker (:proc child)]
          (is (seq @waits))
          (is (every? #{(first @waits)} @waits))
          (is (.waitFor worker cleanup-budget-ms TimeUnit/MILLISECONDS))
          (is (not (.isAlive worker)))
          (is (not (fs/exists? scratch)))
          (is (empty? (fs/list-dir root))))
        (finally (when-let [[child] @observed] (.destroyForcibly ^Process (:proc child))))))))

(defn- ^{:stratum 1} verify-interrupted-wait! [root]
  (let [observed (volatile! nil)
        overrides {#'peer/launch! (partial denial/launch! :timeout observed)
                   (ns-resolve 'ai.miniforge.file-durability.boundary.lock-protocol 'pause!) interrupted-native-wait!}]
    (try+
      (let [reply (with-redefs-fn overrides
                    #(lock/check! (fs/real-path root) (fixtures/require-peer!) fixtures/limits))
            interrupted? (Thread/interrupted)
            [child scratch] @observed
            ^Process worker (:proc child)]
        (is (false? reply))
        (is interrupted?)
        (is (.waitFor worker cleanup-budget-ms TimeUnit/MILLISECONDS))
        (is (not (.isAlive worker)))
        (is (not (fs/exists? scratch)))
        (is (empty? (fs/list-dir root))))
      (finally
        (Thread/interrupted)
        (when-let [[child] @observed] (.destroyForcibly ^Process (:proc child)))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2 :integration true} test-failed-handshakes-refuse-and-release-their-owned-process-and-scratch
  (doseq [mode (keys denial/scenarios)]
    (support/with-root (partial verify-denial! mode))))

(deftest ^{:stratum 2 :integration true} test-interrupted-native-wait-preserves-cancellation-through-cleanup
  (support/with-root verify-interrupted-wait!))

(comment (clojure.test/run-tests 'ai.miniforge.file-durability.lock-denial-integration-test))
