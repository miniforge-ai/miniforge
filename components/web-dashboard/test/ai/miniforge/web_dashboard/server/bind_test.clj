;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.web-dashboard.server.bind-test
  "The dashboard listens on loopback unless told otherwise, and will not
   listen anywhere else without login."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.web-dashboard.server :as server]
            [org.httpkit.server :as http]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} bind-opts
  "Start the dashboard with `opts`, capturing the options http-kit is given
   and aborting startup there. Returns the captured options, or nil when
   startup was refused before a listener was requested."
  [opts]
  (let [captured (atom nil)]
    (with-redefs [http/run-server (fn [_handler run-opts]
                                    (reset! captured run-opts)
                                    (throw (ex-info "stop after bind" {})))]
      (try (server/start-server! (merge {:event-stream (events/create-event-stream {:sinks []})}
                                        opts))
           (catch Exception _ nil)))
    @captured))

(deftest ^{:stratum 0} loopback-host-test
  (is (every? server/loopback-host? ["localhost" "LOCALHOST" "127.0.0.1" "127.1.2.3" "::1" "[::1]"]))
  (is (not-any? server/loopback-host? ["0.0.0.0" "::" "192.168.1.10" "dashboard.example.com"])))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} default-bind-is-loopback-test
  (testing "with no host option and no MINIFORGE_WEB_HOST, the listener binds 127.0.0.1"
    (when-not (System/getenv "MINIFORGE_WEB_HOST")
      (is (= "127.0.0.1" (:ip (bind-opts {})))))))

(deftest ^{:stratum 1} public-bind-requires-login-test
  (testing "a non-loopback host without auth is refused before any listener is requested"
    (let [refusal (try (server/start-server! {:host "0.0.0.0"
                                              :auth {:users {}}
                                              :event-stream (events/create-event-stream {:sinks []})})
                       nil
                       (catch clojure.lang.ExceptionInfo e e))]
      (is (= {:host "0.0.0.0"} (ex-data refusal)))
      (is (re-find #"MINIFORGE_WEB_USERNAME" (ex-message refusal)))
      (is (nil? (bind-opts {:host "0.0.0.0" :auth {:users {}}})) "http-kit is never asked to listen")))

  (testing "with login configured, a non-loopback host is bound as asked"
    (is (= "0.0.0.0" (:ip (bind-opts {:host "0.0.0.0"
                                      :auth {:username "admin" :password "secret"}}))))))
