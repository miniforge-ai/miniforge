;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ^{:miniforge/runtime :jvm-only} ai.miniforge.phase-opsv.prometheus-jvm-test
  "Actual JDK response and close semantics, without network access."
  (:require [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.boundary.prometheus-http :as http]
            [ai.miniforge.phase-opsv.boundary.bounded-io :as io]
            [ai.miniforge.phase-opsv.boundary.http-transport :as transport]
            [ai.miniforge.phase-opsv.boundary.kubernetes-http :as kubernetes]
            [ai.miniforge.phase-opsv.boundary.kubernetes-settings :as kubernetes-settings]
            [ai.miniforge.phase-opsv.kubernetes-http-test-support :as kubernetes-wire]
            [ai.miniforge.phase-opsv.kubernetes-settings-test-support :as target]
            [ai.miniforge.phase-opsv.measurement-test-support :as measurement]
            [ai.miniforge.phase-opsv.prometheus-test-support :as fixture :refer [failed-future]]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [try+]])
  (:import [java.io ByteArrayInputStream PipedInputStream PipedOutputStream]
           [java.net URI]
           [java.net.http HttpHeaders HttpResponse HttpClient$Version HttpTimeoutException HttpConnectTimeoutException]
           [java.nio.charset StandardCharsets]
           [java.util Optional]
           [java.util.function BiPredicate]
           [java.util.concurrent CompletionException FutureTask TimeoutException]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private reader-wait-ms 10000)

(defn- ^{:stratum 0} stalled-body [started closed writer]
  (proxy [PipedInputStream] [writer]
    (readNBytes [limit] (deliver started true) (proxy-super readNBytes limit))
    (close [] (deliver closed true) (proxy-super close))))

(defn- ^{:stratum 0} body [calls text]
  (proxy [ByteArrayInputStream] [(.getBytes ^String text StandardCharsets/UTF_8)]
    (close [] (swap! calls inc))))

(defn- ^{:stratum 0} headers []
  (HttpHeaders/of {} (reify BiPredicate (test [_ _ _] true))))

(defn- ^{:stratum 0} timeout [cause]
  (doto (HttpTimeoutException. "secret timeout") (.initCause cause)))

(deftest ^{:stratum 0} wrapped-http-timeouts-have-consistent-sanitized-diagnostics
  (doseq [failure [(HttpTimeoutException. "secret timeout")
                   (HttpConnectTimeoutException. "secret connect timeout")
                   (CompletionException. (HttpTimeoutException. "secret nested timeout"))]]
    (let [result (fixture/observe (fixture/settings) (failed-future failure) (atom []))]
      (is (= :timeout (:anomaly/type result)))
      (is (not (re-find #"secret" (pr-str result)))))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} timeout-started-reader! [started reader original pending until]
  (if (instance? FutureTask pending)
    (do (deliver reader pending)
        (assert (deref started reader-wait-ms false))
        (throw (TimeoutException.)))
    (original pending until)))

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

(deftest ^{:stratum 2} test-stalled-reader-is-cancelled-and-owned-body-is-closed
  (let [[started closed reader] [(promise) (promise) (promise)]
        original io/await!]
    (with-open [writer (PipedOutputStream.)
                stream (stalled-body started closed writer)]
      (let [pending (fixture/completed (fixture/response "" {:body stream}))]
        (with-redefs [io/await! (partial timeout-started-reader! started reader original)]
          (is (= :timeout (:anomaly/type (fixture/observe (fixture/settings) pending (atom []))))))
        (is (some-> ^FutureTask (deref reader reader-wait-ms nil) .isCancelled))
        (is (true? (deref closed reader-wait-ms false)))))))

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

(deftest ^{:stratum 2} test-kubernetes-real-jdk-bodies-close-on-success-absence-and-rejection
  (let [settings (kubernetes-settings/load-settings (target/settings))]
    (doseq [[status text] [[200 "{}"] [404 ""] [302 ""]]]
      (let [closed (atom 0)
            pending (fixture/completed (response closed status text))]
        (with-redefs [transport/start! (constantly pending)]
          (kubernetes/read-resource! settings (kubernetes-wire/deadline) kubernetes-wire/resource-path {}))
        (is (= 1 @closed))))))

(deftest ^{:stratum 2} test-kubernetes-real-jdk-body-closes-after-malformed-json
  (let [settings (kubernetes-settings/load-settings (target/settings))
        closed (atom 0)
        pending (fixture/completed (response closed 200 "not-json"))]
    (with-redefs [transport/start! (constantly pending)]
      (is (thrown? Exception
                   (kubernetes/read-resource! settings (kubernetes-wire/deadline) kubernetes-wire/resource-path {}))))
    (is (= 1 @closed))))

(comment ::real-jdk-response-acceptance)
