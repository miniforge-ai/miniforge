;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.resource-cleanup.interface-test
  (:require [ai.miniforge.resource-cleanup.interface :as cleanup]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [try+ throw+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} structured-failure []
  (try+ (throw+ {:fixture/failure true})
    (catch Object _ (:throwable &throw-context))))

(defn- ^{:stratum 0} operation [calls label failure]
  (swap! calls conj label)
  (when failure (throw failure)))

(defn- ^{:stratum 0} failure [] (Exception. "fixture failure"))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} run-pair [calls primary secondary]
  (try+ (cleanup/finish! (partial operation calls :primary primary)
                         (partial operation calls :cleanup secondary))
    (catch Object _ (:throwable &throw-context))))

(defn- ^{:stratum 1} failure-cases []
  [[(failure) (failure) :primary]
   [(Error. "fixture fatal") (failure) :primary]
   [(failure) (Error. "fixture fatal") :cleanup]
   [(structured-failure) (failure) :primary]
   [(failure) (structured-failure) :primary]])

(deftest ^{:stratum 1} critical-cause-traversal-terminates-on-ordinary-cycles
  (let [a (failure) b (failure)]
    (.initCause a b)
    (.initCause b a)
    (is (nil? (cleanup/fatal-cause a)))
    (is (nil? (cleanup/fatal-cause nil)))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} cleanup-always-runs-and-failure-priority-is-preserved
  (doseq [[primary secondary winner] (failure-cases)]
    (let [calls (atom [])
          expected (if (= :primary winner) primary secondary)]
      (is (identical? expected (run-pair calls primary secondary)))
      (is (= [:primary :cleanup] @calls)))))

(deftest ^{:stratum 2} success-and-cleanup-only-failure
  (let [calls (atom [])
        secondary (failure)]
    (is (nil? (run-pair calls nil nil)))
    (is (identical? secondary (run-pair calls nil secondary)))
    (is (= [:primary :cleanup :primary :cleanup] @calls))))

(deftest ^{:stratum 2} interruption-restores-thread-flag-after-cleanup
  (let [calls (atom []) interrupted (InterruptedException. "fixture interruption")]
    (try+
      (let [result (run-pair calls interrupted (failure))
            interrupted? (.isInterrupted (Thread/currentThread))]
        (is (identical? interrupted result))
        (is interrupted?)
        (is (= [:primary :cleanup] @calls)))
      (finally (Thread/interrupted)))))

(comment ::shared-resource-boundary-contract)
