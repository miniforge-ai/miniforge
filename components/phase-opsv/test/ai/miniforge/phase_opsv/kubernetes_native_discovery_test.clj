;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-native-discovery-test
  (:require [ai.miniforge.phase-opsv.boundary.kubernetes-client :as client]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.prometheus-test-support :as failures]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} unexpected-capture [calls _settings _target]
  (swap! calls inc))

(defn- ^{:stratum 0} interrupted-discovery! []
  (.interrupt (Thread/currentThread))
  (phase/discover-kubernetes-verification-signals! nil))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} unsupported-native-discovery-never-acquires-or-realizes-test
  (let [calls (atom 0)
        deferred (lazy-seq (throw (ex-info "must not realize configuration" {})))]
    (with-redefs [client/observe-verification! (partial unexpected-capture calls)]
      (doseq [configuration [nil (Object.) deferred]]
        (let [result (phase/discover-kubernetes-verification-signals! configuration)]
          (is (= :invalid-input (:anomaly/type result)))
          (is (= {} (:anomaly/data result)))))
      (is (zero? @calls))
      (is (not (realized? deferred))))))

(deftest ^{:stratum 1} already-interrupted-discovery-starts-no-work-test
  (let [[thrown flagged?] (failures/interrupted-result interrupted-discovery!)]
    (is (instance? InterruptedException thrown))
    (is flagged?)
    (is (false? (.isInterrupted (Thread/currentThread))))))

(comment (clojure.test/run-tests 'ai.miniforge.phase-opsv.kubernetes-native-discovery-test))
