;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-discovery-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.kubernetes-discovery-test-support :as fixture]
            [ai.miniforge.phase-opsv.prometheus-test-support :as failures]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} interrupted-discovery! []
  (.interrupt (Thread/currentThread))
  (fixture/discover! (fixture/configuration) (fixture/scenario)))

(deftest ^{:stratum 0} test-discovery-preserves-correlated-measurement-provenance
  (let [scenario (fixture/scenario)
        [result calls] (fixture/discover! (fixture/configuration) scenario)
        drivers (:discovery/candidate-drivers result)]
    (is (= [:kubernetes :cpu :backlog] calls))
    (is (= [:cpu :backlog] (mapv :driver drivers)))
    (is (= [(:cpu scenario) (:backlog scenario)] (mapv :measurement drivers)))
    (is (= (:kubernetes scenario) (:discovery/kubernetes-fingerprint result)))))

(deftest ^{:stratum 0} test-unbound-metrics-stop-before-later-drivers
  (doseq [labels [(dissoc fixture/labels "service")
                  (assoc fixture/labels "service" "other")
                  (assoc fixture/labels "namespace" "other")
                  (assoc fixture/labels "cluster" "other")]]
    (let [request (get-in (fixture/configuration) [:discovery/queries :cpu])
          scenario (assoc (fixture/scenario) :cpu (fixture/observation request labels))
          [result calls] (fixture/discover! (fixture/configuration) scenario)]
      (is (= :conflict (:anomaly/type result)))
      (is (= [:kubernetes :cpu] calls)))))

(deftest ^{:stratum 0} test-read-failures-are-returned-without-partial-discovery
  (doseq [[kind expected] [[:kubernetes [:kubernetes]]
                         [:cpu [:kubernetes :cpu]]
                         [:backlog [:kubernetes :cpu :backlog]]]]
    (let [failure (anomaly/anomaly :unavailable "fixture failure" {})
          scenario (assoc (fixture/scenario) kind failure)
          [result calls] (fixture/discover! (fixture/configuration) scenario)]
      (is (identical? failure result))
      (is (= expected calls)))))

(deftest ^{:stratum 0} test-malformed-discovery-configuration-starts-no-reads
  (doseq [configuration [nil {}
                         (assoc (fixture/configuration) :discovery/service "")
                         (assoc-in (fixture/configuration) [:discovery/metric-labels :service] "namespace")
                         (update (fixture/configuration) :discovery/queries dissoc :backlog)]]
    (let [[result calls] (fixture/discover! configuration (fixture/scenario))]
      (is (= :invalid-input (:anomaly/type result)))
      (is (empty? calls)))))

(deftest ^{:stratum 0} test-one-query-cannot-pretend-to-be-two-candidate-drivers
  (let [configuration (fixture/configuration)
        cpu (get-in configuration [:discovery/queries :cpu])
        duplicated (assoc-in configuration [:discovery/queries :backlog] cpu)
        [result calls] (fixture/discover! duplicated (fixture/scenario))]
    (is (= :invalid-input (:anomaly/type result)))
    (is (empty? calls))))

(deftest ^{:stratum 0} test-discovery-preserves-fatal-and-interrupted-outcomes
  (doseq [kind [:kubernetes :cpu :backlog]
          failure [(AssertionError.) (InterruptedException.)]]
    (let [scenario (assoc (fixture/scenario) kind failure)
          operation (partial fixture/discover! (fixture/configuration) scenario)
          [thrown flagged?] (failures/interrupted-result operation)]
      (is (identical? failure thrown))
      (is (= (instance? InterruptedException failure) flagged?)))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} test-already-interrupted-discovery-preserves-the-thread-flag
  (let [[thrown flagged?] (failures/interrupted-result interrupted-discovery!)]
    (is (instance? InterruptedException thrown))
    (is flagged?)
    (is (false? (.isInterrupted (Thread/currentThread))))))

(comment ::read-only-discovery-not-experiment-authority)
