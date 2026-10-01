;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.cli.main.launchers-test
  (:require [ai.miniforge.cli.main.launchers :as launchers]
            [ai.miniforge.cli.main.util :as util]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.pr-train.interface :as pr-train]
            [ai.miniforge.repo-dag.interface :as repo-dag]
            [ai.miniforge.supervisory-state.interface :as supervisory]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} record-call [calls stage value & _]
  (swap! calls conj stage)
  value)

(defn- ^{:stratum 0} fail-with [failure & _] (throw failure))

(defn- ^{:stratum 0} interruption-preserved? [create]
  (try+ (create) false
       (catch InterruptedException _ (.isInterrupted (Thread/currentThread)))
       (finally (Thread/interrupted))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} optional-dashboard-setup-preserves-event-wiring-order
  (let [calls (atom [])
        start (partial record-call calls :start :started)]
    (with-redefs [util/optional-composition-var (constantly start)
                  events/create-event-stream (partial record-call calls :stream :events)
                  supervisory/ensure-attached! (partial record-call calls :attach nil)
                  pr-train/create-manager (partial record-call calls :train :train)
                  repo-dag/create-manager (partial record-call calls :repo :repo)]
      (let [launch (launchers/optional-web-launcher)]
        (is (empty? @calls))
        (is (= :started (launch {:port 7878})))
        (is (= [:stream :attach :train :repo :start] @calls)))))
  (with-redefs [util/optional-composition-var (constantly nil)]
    (is (nil? (launchers/optional-web-launcher)))))

(deftest ^{:stratum 1} optional-manager-boundaries-preserve-fatal-errors
  (let [fatal (partial fail-with (AssertionError. "fatal fixture"))]
    (with-redefs [pr-train/create-manager fatal repo-dag/create-manager fatal]
      (is (thrown? AssertionError (launchers/create-pr-train-manager)))
      (is (thrown? AssertionError (launchers/create-repo-dag-manager))))))

(deftest ^{:stratum 1} optional-manager-boundaries-preserve-interruption
  (let [interrupted (partial fail-with (InterruptedException.))]
    (doseq [create [launchers/create-pr-train-manager launchers/create-repo-dag-manager]]
      (with-redefs [pr-train/create-manager interrupted repo-dag/create-manager interrupted]
        (is (true? (interruption-preserved? create)))))))

(deftest ^{:stratum 1} optional-resolution-preserves-direct-and-wrapped-fatal-causes
  (doseq [wrap [identity (partial ex-info "loader fixture" {})]]
    (with-redefs [clojure.core/require (partial fail-with (wrap (AssertionError. "fatal fixture")))]
      (is (thrown? AssertionError (launchers/optional-web-launcher))))
    (with-redefs [clojure.core/require (partial fail-with (wrap (InterruptedException.)))]
      (is (true? (interruption-preserved? launchers/optional-web-launcher))))))

(deftest ^{:stratum 1} optional-resolution-still-tolerates-absent-components
  (with-redefs [clojure.core/require (partial fail-with (java.io.FileNotFoundException.))]
    (is (nil? (launchers/optional-web-launcher)))))

(comment
  ::optional-web-launcher)
