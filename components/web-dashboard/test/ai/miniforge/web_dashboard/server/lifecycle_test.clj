;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.web-dashboard.server.lifecycle-test
  (:require [clojure.test :refer [deftest is]]
            [org.httpkit.server :as http]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.web-dashboard.control-identity :as control-identity]
            [ai.miniforge.web-dashboard.server :as server]
            [ai.miniforge.web-dashboard.server.shutdown :as shutdown]
            [ai.miniforge.web-dashboard.server.startup :as startup]
            [ai.miniforge.web-dashboard.watcher :as watcher]
            [ai.miniforge.web-dashboard.state.core :as state]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} wait-ms 5000)

(defn- ^{:stratum 0} fail! [& _] (throw (ex-info "Lifecycle failure" {})))

(defn- ^{:stratum 0} completed-stop [_] (delay true))

(defn- ^{:stratum 0} stop-after-signal [stopping stopped _server]
  (deliver stopping true)
  stopped)

(defn- ^{:stratum 0} dashboard [stream]
  (control-identity/attach! (state/create-state {:event-stream stream})))

(deftest ^{:stratum 0} startup-gates-requests-until-identity-is-attached
  (let [stream (events/create-event-stream {:sinks []})
        dashboard-state (state/create-state {:event-stream stream})
        calls (atom 0)
        handler (control-identity/ready-handler dashboard-state (fn [_] (swap! calls inc)))]
    (is (= 503 (:status (handler {}))))
    (is (zero? @calls))
    (control-identity/attach! dashboard-state)
    (is (= 1 (handler {})))
    (control-identity/release! dashboard-state)))

(deftest ^{:stratum 0} rollback-preserves-the-original-failure
  (doseq [same? [true false]]
    (let [failure (ex-info "Startup failure" {})
          cleanup (if same? failure (ex-info "Cleanup failure" {}))]
      (with-redefs [shutdown/stop! (fn [_] (throw cleanup))]
        (let [result (try (startup/acquire! (fn [_] (throw failure)))
                         (catch Throwable error error))]
          (is (identical? failure result))
          (is (= (if same? [] [cleanup]) (vec (.getSuppressed failure)))))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} startup-failure-does-not-acquire-a-listener
  (let [stream (events/create-event-stream {:sinks []})]
    (with-redefs [http/run-server (fn [handler _opts]
                                  (is (= 503 (:status (handler {}))))
                                  (fail!))]
      (is (thrown? Exception (server/start-server! {:event-stream stream}))))
    (is (empty? (events/list-listeners stream)))))

(deftest ^{:stratum 1} startup-failure-rolls-back-acquired-resources
  (doseq [stage [:watcher :identity]]
    (let [stream (events/create-event-stream {:sinks []})
          closed (atom [])
          attach! control-identity/attach!]
      (with-redefs [http/run-server (constantly :fake)
                    http/server-port (constantly 7878)
                    http/server-stop! (fn [_] (swap! closed conj :http) (delay true))
                    clojure.core/future-call (fn [_] (delay nil))
                    watcher/start-watcher! (fn [& _]
                                             (if (= :watcher stage) (fail!) #(swap! closed conj :watcher)))
                    control-identity/attach! (fn [state] (attach! state) (fail!))]
        (binding [*out* (java.io.StringWriter.)]
          (is (thrown? Exception (server/start-server! {:event-stream stream})))))
      (is (= (if (= :watcher stage) [:http] [:http :watcher]) @closed))
      (is (empty? (events/list-listeners stream))))))

(deftest ^{:stratum 1} shutdown-drains-http-before-releasing-the-listener
  (let [stream (events/create-event-stream {:sinks []})
        dashboard-state (dashboard stream)
        stopping (promise)
        stopped (promise)]
    (with-redefs [http/server-stop! (partial stop-after-signal stopping stopped)
                  server/delete-discovery-file! (constantly nil)]
      (let [closing (future (server/stop-server! {:server :fake :state dashboard-state}))]
        (try
          (is (= true (deref stopping wait-ms :timeout)))
          (is (= 1 (count (events/list-listeners stream))))
          (is (not (realized? closing)))
          (finally (deliver stopped true)))
        (is (not= :timeout (deref closing wait-ms :timeout)))
        (is (empty? (events/list-listeners stream)))))))

(deftest ^{:stratum 1} shutdown-failures-still-release-the-listener
  (doseq [http-fails? [true false]]
    (let [stream (events/create-event-stream {:sinks []})
          dashboard-state (dashboard stream)
          stop-http (if http-fails? fail! completed-stop)
          stop-watcher (if http-fails? (constantly nil) fail!)]
      (with-redefs [http/server-stop! stop-http
                    server/delete-discovery-file! (constantly nil)]
        (is (thrown? Exception (server/stop-server!
                               {:server :fake :state dashboard-state :watcher-cleanup stop-watcher}))))
      (is (empty? (events/list-listeners stream))))))
