;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0

(ns ai.miniforge.progress-detector.protocol-test
  "Tests for the Detector protocol, NullDetector, and MultiDetector."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.progress-detector.protocol :as sut]))

;------------------------------------------------------------------------------ Layer 0
;; Helpers

(defn- make-obs
  ([seq-num]
   (make-obs seq-num :tool/bash))
  ([seq-num tool-id]
   {:tool/id          tool-id
    :seq              seq-num
    :timestamp        (java.time.Instant/now)
    :tool/duration-ms 100}))

;------------------------------------------------------------------------------ Layer 1
;; NullDetector

(deftest null-detector-init-test
  (testing "init returns expected initial shape"
    (let [det   (sut/null-detector)
          state (sut/init det {})]
      (is (vector? (:anomalies state))    "anomalies must be a vector")
      (is (empty?  (:anomalies state))    "anomalies must be empty initially")
      (is (vector? (:observations state)) "observations must be a vector")
      (is (empty?  (:observations state)) "observations must be empty initially")
      (is (= 0 (:window-size state))      "window-size must start at 0"))))

(deftest null-detector-observe-test
  (testing "observe accumulates observations without flagging anomalies"
    (let [det   (sut/null-detector)
          state (sut/init det {})
          obs1  (make-obs 1)
          obs2  (make-obs 2)
          st1   (sut/observe det state obs1)
          st2   (sut/observe det st1   obs2)]
      (is (= 1 (count (:observations st1))) "one observation after first step")
      (is (= 2 (count (:observations st2))) "two observations after second step")
      (is (empty? (:anomalies st2))          "NullDetector never flags anomalies")
      (is (= 2 (:window-size st2))           "window-size tracks call count"))))

(deftest null-detector-immutability-test
  (testing "observe does not mutate its state argument"
    (let [det   (sut/null-detector)
          state (sut/init det {})
          orig  (dissoc state :__volatile__)
          _     (sut/observe det state (make-obs 1))]
      (is (= 0 (:window-size state)) "original state unchanged after observe"))))

(deftest null-detector-pure-test
  (testing "identical input sequence produces identical output"
    (let [det    (sut/null-detector)
          now    (java.time.Instant/parse "2026-01-01T00:00:00Z")
          obs    {:tool/id :tool/bash :seq 1 :timestamp now}
          state  (sut/init det {})
          result-a (sut/observe det state obs)
          result-b (sut/observe det state obs)]
      (is (= result-a result-b) "identical inputs produce identical outputs"))))

;------------------------------------------------------------------------------ Layer 1
;; MultiDetector

(deftest multi-detector-init-test
  (testing "MultiDetector initializes all sub-detectors"
    (let [d1  (sut/null-detector)
          d2  (sut/null-detector)
          det (sut/multi-detector [d1 d2])
          st  (sut/init det {})]
      (is (= 2 (count (:sub-states st))) "two sub-states for two detectors")
      (is (empty? (:anomalies st)))
      (is (empty? (:observations st))))))

(deftest multi-detector-observe-fans-out-test
  (testing "observe fans to all children and merges anomalies"
    (let [d1  (sut/null-detector)
          d2  (sut/null-detector)
          det (sut/multi-detector [d1 d2])
          st0 (sut/init det {})
          st1 (sut/observe det st0 (make-obs 1))]
      ;; Both sub-states should have seen the observation
      (is (= 1 (:window-size (nth (:sub-states st1) 0))))
      (is (= 1 (:window-size (nth (:sub-states st1) 1))))
      (is (empty? (:anomalies st1))))))

(deftest multi-detector-empty-test
  (testing "MultiDetector with no children is valid"
    (let [det (sut/multi-detector [])
          st  (sut/init det {})]
      (is (empty? (:sub-states st)))
      (is (empty? (:anomalies st))))))

;------------------------------------------------------------------------------ Layer 1
;; reduce-observations

(deftest reduce-observations-test
  (testing "reduce-observations folds all observations"
    (let [det  (sut/null-detector)
          obss (map make-obs (range 5))
          st   (sut/reduce-observations det {} obss)]
      (is (= 5 (count (:observations st))))
      (is (= 5 (:window-size st)))))

  (testing "reduce-observations on empty seq returns init state"
    (let [det (sut/null-detector)
          st  (sut/reduce-observations det {} [])]
      (is (empty? (:observations st)))
      (is (= 0 (:window-size st))))))
