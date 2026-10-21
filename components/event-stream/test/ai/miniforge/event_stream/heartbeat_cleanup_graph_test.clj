;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.heartbeat-cleanup-graph-test
  (:require [ai.miniforge.event-stream.boundary.heartbeat-cleanup :as cleanup]
            [ai.miniforge.event-stream.boundary.throwable-graph :as graph]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} failure [] (Exception. "fixture failure"))

(defn- ^{:stratum 0} propagate [exception] (throw exception))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} retained-failure [primary secondary]
  (try+ (cleanup/after-failure! primary (partial propagate secondary))
    (catch Object _ (:throwable &throw-context))))

(deftest ^{:stratum 1} preexisting-cycles-terminate
  (let [primary (failure) secondary (failure) intermediate (failure)]
    (.initCause secondary intermediate)
    (.initCause intermediate secondary)
    (is (false? (graph/reaches? secondary primary)))
    (is (true? (graph/reaches? secondary intermediate)))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} wrapping-cleanup-does-not-create-a-cycle
  (let [primary (failure)
        intermediate (failure)
        secondary (failure)]
    (.initCause intermediate primary)
    (.initCause secondary intermediate)
    (is (identical? primary (retained-failure primary secondary)))
    (is (empty? (.getSuppressed primary)))))

(deftest ^{:stratum 2} suppression-links-are-also-cycle-checked
  (let [primary (failure) secondary (failure)]
    (.addSuppressed secondary primary)
    (is (identical? primary (retained-failure primary secondary)))
    (is (empty? (.getSuppressed primary)))))

(deftest ^{:stratum 2} independent-cleanup-failures-are-retained
  (let [primary (failure) secondary (failure)]
    (is (identical? primary (retained-failure primary secondary)))
    (is (= [secondary] (vec (.getSuppressed primary))))))

(deftest ^{:stratum 2} self-suppression-is-rejected
  (let [primary (failure)]
    (is (identical? primary (retained-failure primary primary)))
    (is (empty? (.getSuppressed primary)))))

(comment ::exception-graph-regressions)
