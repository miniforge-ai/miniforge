;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ^{:miniforge/runtime :jvm-only} ai.miniforge.phase-opsv.prometheus-jvm-test
  "Actual JDK response and close semantics, without network access."
  (:require [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.boundary.prometheus-http :as http]
            [ai.miniforge.phase-opsv.boundary.prometheus-io :as io]
            [ai.miniforge.phase-opsv.measurement-test-support :as measurement]
            [ai.miniforge.phase-opsv.prometheus-test-support :as fixture]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [try+]])
  (:import [java.io ByteArrayInputStream]
           [java.net URI]
           [java.net.http HttpHeaders HttpResponse HttpClient$Version HttpTimeoutException HttpConnectTimeoutException]
           [java.nio.charset StandardCharsets]
           [java.util Optional]
           [java.util.function BiPredicate]
           [java.util.concurrent CompletableFuture CompletionException]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} body [calls text]
  (proxy [ByteArrayInputStream] [(.getBytes ^String text StandardCharsets/UTF_8)]
    (close [] (swap! calls inc))))

(defn- ^{:stratum 0} headers []
  (HttpHeaders/of {} (reify BiPredicate (test [_ _ _] true))))

(defn- ^{:stratum 0} failed-future [failure]
  (doto (CompletableFuture.) (.completeExceptionally failure)))

(defn- ^{:stratum 0} timeout [cause]
  (doto (HttpTimeoutException. "secret timeout") (.initCause cause)))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} wrapped-http-timeouts-have-consistent-sanitized-diagnostics
  (doseq [failure [(HttpTimeoutException. "secret timeout")
                   (HttpConnectTimeoutException. "secret connect timeout")
                   (CompletionException. (HttpTimeoutException. "secret nested timeout"))]]
    (let [result (fixture/observe (fixture/settings) (failed-future failure) (atom []))]
      (is (= :timeout (:anomaly/type result)))
      (is (not (re-find #"secret" (pr-str result)))))))

(deftest ^{:stratum 1} timeout-wrappers-never-mask-critical-causes
  (is (thrown? AssertionError
               (fixture/observe (fixture/settings)
                                (failed-future (timeout (AssertionError. "fatal timeout cause"))) (atom []))))
  (try+
    (fixture/observe (fixture/settings) (failed-future (timeout (InterruptedException.))) (atom []))
    (is false)
    (catch InterruptedException _
      (let [flagged? (.isInterrupted (Thread/currentThread))] (is flagged?)))
    (finally (Thread/interrupted))))

(defn- ^{:stratum 1} response [calls status text]
  (let [stream (body calls text)
        headers (headers)]
    (reify HttpResponse
      (statusCode [_] status)
      (body [_] stream)
      (headers [_] headers)
      (request [_] nil)
      (previousResponse [_] (Optional/empty))
      (sslSession [_] (Optional/empty))
      (uri [_] (URI. "https://metrics.invalid/api/v1/query_range"))
      (version [_] HttpClient$Version/HTTP_1_1))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} real-jdk-bodies-are-closed-on-success-rejection-and-parse-failure
  (doseq [[status text] [[200 (fixture/successful-text)] [302 ""] [200 "not-json"]]]
    (let [closed (atom 0)
          pending (fixture/completed (response closed status text))
          calls (atom [])]
      (with-redefs [http/start! (partial fixture/record-request! calls pending)]
        (phase/observe-prometheus-window! (fixture/settings) (measurement/request)))
      (is (= 1 @closed)))))

(deftest ^{:stratum 2} completed-response-is-closed-when-the-header-deadline-wins
  (let [closed (atom 0)
        pending (fixture/completed (response closed 200 ""))
        calls (atom [])]
    (with-redefs [http/start! (partial fixture/record-request! calls pending)
                  io/remaining-ms (constantly 0)]
      (is (= :timeout (:anomaly/type
                       (phase/observe-prometheus-window! (fixture/settings) (measurement/request))))))
    (is (= 1 @closed))))

(comment ::real-jdk-response-acceptance)
