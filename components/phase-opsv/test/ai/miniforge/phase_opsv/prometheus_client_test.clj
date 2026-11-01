;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.prometheus-client-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.boundary.prometheus-http :as http]
            [ai.miniforge.phase-opsv.boundary.prometheus-io :as io]
            [ai.miniforge.phase-opsv.measurement-test-support :as measurement]
            [ai.miniforge.phase-opsv.prometheus-test-support :as fixture :refer [observe]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [try+]])
  (:import [java.util.concurrent CompletableFuture TimeoutException]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} failed-future [thrown]
  (doto (CompletableFuture.) (.completeExceptionally thrown)))

(defn- ^{:stratum 0} read-timeout! [& _] (throw (TimeoutException.)))

(defn- ^{:stratum 0} fatal-read! [& _] (throw (AssertionError. "read failed")))

(defn- ^{:stratum 0} interrupted-result [operation]
  (try+ (operation) nil
    (catch Object _
      [(:throwable &throw-context) (.isInterrupted (Thread/currentThread))])))

(deftest ^{:stratum 0} observations-retain-the-declared-query-grid-and-zero-samples
  (let [calls (atom [])
        pending (fixture/completed (fixture/response (fixture/successful-text)))
        result (observe (fixture/settings) pending calls)]
    (is (= (:prometheus/query (measurement/request)) (:prometheus/query result)))
    (is (= [[100 2.0] [110 3.0] [120 0.0]] (:measurement/samples result)))
    (is (= 1 (count @calls)))
    (is (not (contains? result :prometheus/bearer-token)))))

(deftest ^{:stratum 0} invalid-input-never-starts-transport
  (let [calls (atom [])
        pending (CompletableFuture.)]
    (is (anomaly/anomaly? (observe {} pending calls)))
    (is (anomaly/anomaly? (observe (fixture/settings {:measurement/max-samples 2}) pending calls)))
    (is (empty? @calls))))

(deftest ^{:stratum 0} rejected-response-bodies-never-become-partial-observations
  (doseq [response [(fixture/response "not-json")
                    (fixture/response (str (fixture/successful-text) " {}"))
                    (fixture/response (str (fixture/successful-text) " trailing-data"))
                    (fixture/response (str/replace (fixture/successful-text) "\"status\":\"success\""
                                                   "\"status\":\"error\",\"status\":\"success\""))
                    (fixture/response (fixture/successful-text) {:status 302})
                    (fixture/response (fixture/successful-text) {:status 503})
                    (fixture/response (fixture/successful-text) {:encodings ["gzip"]})]]
    (is (anomaly/anomaly? (observe (fixture/settings) (fixture/completed response) (atom [])))))
  (is (anomaly/anomaly?
       (observe (fixture/settings {:prometheus/max-response-bytes 3})
                (fixture/completed (fixture/response (fixture/successful-text))) (atom [])))))

(deftest ^{:stratum 0} header-deadline-cancels-the-original-future
  (let [pending (CompletableFuture.)]
    (with-redefs [io/remaining-ms (constantly 0)]
      (is (= :timeout (:anomaly/type (observe (fixture/settings) pending (atom []))))))
    (is (.isCancelled pending))))

(deftest ^{:stratum 0} wire-timestamp-precision-is-not-rounded-onto-the-declared-grid
  (doseq [timestamp ["100.000000000000001" "1.00000000000000001e2"]]
    (let [text (str/replace (fixture/successful-text) "[100," (str "[" timestamp ","))
          pending (fixture/completed (fixture/response text))]
      (is (anomaly/anomaly? (observe (fixture/settings) pending (atom []))))))
  (let [text (str/replace (fixture/successful-text) "[100," "[100.0,")
        pending (fixture/completed (fixture/response text))]
    (is (not (anomaly/anomaly? (observe (fixture/settings) pending (atom [])))))))

(deftest ^{:stratum 0} synchronous-timeout-never-masks-a-fatal-cause
  (let [failure (doto (TimeoutException.) (.initCause (AssertionError.)))]
    (with-redefs [http/start! (fn [& _] (throw failure))]
      (is (thrown? AssertionError
                   (phase/observe-prometheus-window! (fixture/settings) (measurement/request)))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} body-deadline-and-network-errors-are-sanitized
  (with-redefs [io/read-bytes! read-timeout!]
    (is (= :timeout (:anomaly/type
                     (observe (fixture/settings)
                              (fixture/completed (fixture/response "secret")) (atom []))))))
  (let [pending (failed-future (java.io.IOException. "secret bearer token"))
        result (observe (fixture/settings) pending (atom []))]
    (is (= :unavailable (:anomaly/type result)))
    (is (not (re-find #"secret" (pr-str result))))))

(deftest ^{:stratum 1} fatal-and-wrapped-interruption-causes-are-never-observations
  (let [failure (doto (TimeoutException.) (.initCause (AssertionError.)))]
    (with-redefs [io/read-bytes! (fn [& _] (throw failure))]
      (is (thrown? AssertionError
                   (observe (fixture/settings)
                            (fixture/completed (fixture/response "")) (atom []))))))
  (with-redefs [io/read-bytes! fatal-read!]
    (is (thrown? AssertionError
                 (observe (fixture/settings)
                          (fixture/completed (fixture/response "")) (atom [])))))
  (let [pending (failed-future (InterruptedException.))]
    (try+
      (let [[failure flagged?] (interrupted-result (partial observe (fixture/settings) pending (atom [])))]
        (is (instance? InterruptedException failure))
        (is flagged?))
      (finally (Thread/interrupted)))))

(deftest ^{:stratum 1} pre-existing-interruption-prevents-network-start
  (let [calls (atom [])]
    (try+
      (.interrupt (Thread/currentThread))
      (let [[failure flagged?] (interrupted-result
                                (partial observe (fixture/settings) (CompletableFuture.) calls))]
        (is (instance? InterruptedException failure))
        (is flagged?)
        (is (empty? @calls)))
      (finally (Thread/interrupted)))))

(comment ::read-only-client-boundary-acceptance)
