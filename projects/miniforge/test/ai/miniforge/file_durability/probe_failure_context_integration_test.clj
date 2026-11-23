;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.probe-failure-context-integration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.file-durability.boundary :as boundary]
            [ai.miniforge.file-durability.probe-cleanup-fixtures :as native]
            [ai.miniforge.file-durability.probe-resource-fixtures :as ownership]
            [ai.miniforge.file-durability.publication-support :as support]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} verify-ordinary-failure! [parent child _handles]
  (let [primary (java.io.IOException.)
        observation (ownership/capture-retained! parent child primary)
        actual (:actual observation)
        thrown (:thrown actual)
        public (ownership/translated parent actual)
        context (get-in public [:anomaly/data :durability/primary-failure])]
    (is (= :anomalies.file-durability/unreaped-peer (anomaly/subtype public)))
    (is (= (str (:root observation)) (get-in public [:anomaly/data :durability/path])))
    (is (identical? primary (.getCause ^Throwable thrown)))
    (is (anomaly/anomaly? context))
    (is (= :fault (:anomaly/type context)))
    (is (= :native-probe (get-in context [:anomaly/data :durability/operation])))
    (is (.isAlive ^Process (:proc child)))
    (is (fs/exists? (:root observation)))))

(defn- ^{:stratum 0} verify-interrupted-failure! [parent child _handles]
  (let [primary (InterruptedException.)
        observation (ownership/capture-retained! parent child primary)]
    (is (identical? primary (get-in observation [:actual :thrown])))
    (is (:interrupted? observation))
    (is (= 1 (count (.getSuppressed primary))))
    (is (.isAlive ^Process (:proc child)))
    (is (fs/exists? (:root observation)))))

(defn- ^{:stratum 0} verify-returned-anomaly! [parent child _handles]
  (let [primary (boundary/unverified-publication parent)
        observation (ownership/capture-retained! parent child primary)
        public (ownership/translated parent (:actual observation))]
    (is (= :anomalies.file-durability/unreaped-peer (anomaly/subtype public)))
    (is (identical? primary (get-in public [:anomaly/data :durability/primary-failure])))
    (is (= (str (:root observation)) (get-in public [:anomaly/data :durability/path])))
    (is (.isAlive ^Process (:proc child)))
    (is (fs/exists? (:root observation)))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} with-ordinary-failure! [parent]
  (native/with-native! :single (partial verify-ordinary-failure! parent)))

(defn- ^{:stratum 1} with-interrupted-failure! [parent]
  (native/with-native! :single (partial verify-interrupted-failure! parent)))

(defn- ^{:stratum 1} with-returned-anomaly! [parent]
  (native/with-native! :single (partial verify-returned-anomaly! parent)))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2 :integration true} test-io-failure-retains-unreaped-context-through-generic-translation
  (support/with-root with-ordinary-failure!))

(deftest ^{:stratum 2 :integration true} test-unconfirmed-termination-cannot-replace-primary-interruption
  (support/with-root with-interrupted-failure!))

(deftest ^{:stratum 2 :integration true} test-returned-operation-anomaly-survives-unconfirmed-termination
  (support/with-root with-returned-anomaly!))

(comment (clojure.test/run-tests 'ai.miniforge.file-durability.probe-failure-context-integration-test))
