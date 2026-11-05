;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-native-discovery-integration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.kubernetes-capture-test-support :as wire]
            [ai.miniforge.phase-opsv.kubernetes-discovery-test-support :as discovery]
            [ai.miniforge.phase-opsv.kubernetes-native-discovery-fixtures :as fixtures]
            [ai.miniforge.phase-opsv.prometheus-test-support :as failures]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} assert-no-reads [input configuration]
  (let [[result calls] (fixtures/observe! configuration (:routes input) (:measurements input))]
    (is (= :invalid-input (:anomaly/type result)))
    (is (= {} (:anomaly/data result)))
    (is (empty? calls))))

(deftest ^{:stratum 0} complete-native-baseline-and-correlated-drivers-preserve-originals-test
  (let [input (fixtures/input)
        [result calls] (fixtures/observe! (:configuration input) (:routes input) (:measurements input))
        projected (:discovery/kubernetes-verification result)
        drivers (:discovery/candidate-drivers result)
        reads (fixtures/kubernetes-calls calls)
        metrics (fixtures/measurement-calls calls)]
    (is (not (anomaly/anomaly? result)))
    (is (= (:projected input) projected))
    (is (= #{:cluster :node-pools :image-digests :config-hash} (set (keys (:environment-fingerprint projected)))))
    (is (= [:cpu :backlog] (mapv :driver drivers)))
    (is (= (mapv (:measurements input) [:cpu :backlog]) (mapv :measurement drivers)))
    (is (seq reads))
    (is (every? #(= :get (:method %)) reads))
    (is (not-any? #(contains? (:query-params %) :labelSelector) reads))
    (is (= ["/api/v1/namespaces/staging" "/apis/apps/v1/namespaces/staging/deployments/catalog"
            "/api/v1/namespaces/kube-system"] (mapv wire/path (take-last 3 reads))))
    (is (= [:cpu :backlog] (mapv :measurement/driver metrics)))
    (is (= metrics (take-last 2 calls)))
    (is (= (get-in input [:configuration :discovery/queries])
           (into {} (map (juxt :measurement/driver :request) metrics))))))

(deftest ^{:stratum 0} repeated-native-discovery-does-not-cache-the-baseline-test
  (let [input (fixtures/input)
        [before _first] (fixtures/observe! (:configuration input) (:routes input) (:measurements input))
        [after _next] (fixtures/observe! (:configuration input) (fixtures/changed-configuration (:routes input)) (:measurements input))]
    (is (not (anomaly/anomaly? before)))
    (is (not (anomaly/anomaly? after)))
    (is (not= (get-in before [:discovery/kubernetes-verification :environment-fingerprint :config-hash])
              (get-in after [:discovery/kubernetes-verification :environment-fingerprint :config-hash])))))

(deftest ^{:stratum 0} incomplete-runtime-stops-before-metric-acquisition-test
  (let [input (fixtures/input)
        [result calls] (fixtures/observe! (:configuration input) (fixtures/missing-runtime (:routes input)) (:measurements input))]
    (is (anomaly/anomaly? result))
    (is (= {} (:anomaly/data result)))
    (is (seq (fixtures/kubernetes-calls calls)))
    (is (empty? (fixtures/measurement-calls calls)))))

(deftest ^{:stratum 0} wrong-native-identity-and-out-of-contract-counts-stop-before-metrics-test
  (let [input (fixtures/input)
        configuration (:configuration input)]
    (doseq [changed [(assoc-in configuration [:discovery/declaration :runtime/target :namespace-uid] "other")
                     (assoc-in configuration [:discovery/replica-bounds :replicas/min-count] 3)]]
      (let [[result calls] (fixtures/observe! changed (:routes input) (:measurements input))]
        (is (anomaly/anomaly? result))
        (is (not (contains? result :discovery/kubernetes-verification)))
        (is (seq (fixtures/kubernetes-calls calls)))
        (is (empty? (fixtures/measurement-calls calls)))))))

(deftest ^{:stratum 0} settings-cannot-redirect-the-independent-target-test
  (let [input (fixtures/input)
        configuration (assoc-in (:configuration input) [:discovery/kubernetes :kubernetes/cluster-id] "other")
        [result calls] (fixtures/observe! configuration (:routes input) (:measurements input))]
    (is (anomaly/anomaly? result))
    (is (= {} (:anomaly/data result)))
    (is (empty? calls))))

(deftest ^{:stratum 0} native-discovery-rejects-unbound-or-failed-metrics-without-partial-output-test
  (let [input (fixtures/input)
        request (get-in input [:configuration :discovery/queries :cpu])
        unbound (discovery/observation request (assoc discovery/labels "service" "other"))
        failure (anomaly/anomaly :unavailable "fixture refusal" {})]
    (doseq [[kind value expected] [[:cpu unbound [:cpu]]
                                  [:cpu failure [:cpu]]
                                  [:backlog failure [:cpu :backlog]]]]
      (let [[result calls] (fixtures/observe! (:configuration input) (:routes input) (assoc (:measurements input) kind value))]
        (is (anomaly/anomaly? result))
        (is (= {} (:anomaly/data result)))
        (is (= expected (mapv :measurement/driver (fixtures/measurement-calls calls))))))))

(deftest ^{:stratum 0} native-discovery-preserves-interrupted-and-fatal-outcomes-test
  (let [input (fixtures/input)]
    (doseq [failure [(AssertionError.) (InterruptedException.)]]
      (let [operation (partial fixtures/observe! (:configuration input) (:routes input) (assoc (:measurements input) :cpu failure))
            [thrown flagged?] (failures/interrupted-result operation)]
        (is (identical? failure thrown))
        (is (= (instance? InterruptedException failure) flagged?))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} malformed-target-bounds-and-hidden-values-start-no-reads-test
  (let [input (fixtures/input)
        configuration (:configuration input)
        deferred (lazy-seq (throw (ex-info "must not realize hidden configuration" {})))]
    (doseq [changed [(dissoc configuration :discovery/declaration)
                     (dissoc configuration :discovery/replica-bounds)
                     (assoc-in configuration [:discovery/replica-bounds :replicas/min-count] 4)
                     (assoc-in configuration [:discovery/declaration :runtime/target :workload-uid] "")
                     (assoc-in configuration [:discovery/queries :backlog] (get-in configuration [:discovery/queries :cpu]))
                     (with-meta configuration {:hidden deferred})]]
      (assert-no-reads input changed))
    (is (not (realized? deferred)))))

(comment (clojure.test/run-tests 'ai.miniforge.phase-opsv.kubernetes-native-discovery-integration-test))
