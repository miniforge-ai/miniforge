;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.web-dashboard.control-evidence-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [cheshire.core :as json]
            [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.response.interface :as response]
            [ai.miniforge.web-dashboard.control-identity :as control-identity]
            [ai.miniforge.web-dashboard.messages :as messages]
            [ai.miniforge.web-dashboard.server :as server]
            [ai.miniforge.web-dashboard.server.handlers :as handlers]
            [ai.miniforge.web-dashboard.state.core :as state]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} justification "Pause for operator investigation")

(def ^{:stratum 0} parameters {:reason "investigation"})

(def ^{:stratum 0} failure-message "Intervention unavailable")

(def ^{:stratum 0} wait-ms 5000)

(defn- ^{:stratum 0} dashboard [stream]
  (control-identity/attach! (state/create-state {:event-stream stream})))

(defn- ^{:stratum 0} registered-listener [stream listener-id]
  (first (filter #(= listener-id (:listener/id %)) (events/list-listeners stream))))

(defn- ^{:stratum 0} recorded-actions [stream workflow-id]
  (:evidence/control-actions
   (evidence/assemble-evidence-bundle workflow-id {:workflow/status :completed}
                                      nil {:event-stream stream})))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} control-body []
  (json/generate-string
   {:action/type :pause
    :action/justification justification
    :action/parameters parameters
    :action/requester {:principal "spoofed" :role :admin :listener-id (random-uuid)}}))

(defn- ^{:stratum 1} fail-intervention [_request]
  (throw (ex-info failure-message {:operation :pause})))

(deftest ^{:stratum 1} dashboard-listener-lifetime-is-per-instance
  (let [stream (events/create-event-stream {:sinks []})
        first-state (dashboard stream)
        second-state (dashboard stream)
        first-id (get-in @first-state [:control/requester :listener-id])
        second-id (get-in @second-state [:control/requester :listener-id])]
    (is (not= first-id second-id))
    (server/stop-server! {:state first-state})
    (is (nil? (registered-listener stream first-id)))
    (is (some? (registered-listener stream second-id)))
    (server/stop-server! {:state second-state})
    (is (empty? (events/list-listeners stream)))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} dashboard-controls-retain-registered-identity-in-evidence
  (let [stream (events/create-event-stream {:sinks []})
        dashboard-state (dashboard stream)
        requester (:control/requester @dashboard-state)
        workflow-id (random-uuid)]
    (with-redefs [events/request-intervention! identity]
      (dotimes [_ 2]
        (is (= 200 (:status (handlers/handle-api-workflow-command-v2
                            dashboard-state workflow-id (control-body)))))))
    (let [records (recorded-actions stream workflow-id)
          record (first records)
          listener (registered-listener stream (:listener-id requester))]
      (is (= 2 (count records)))
      (is (= [requester requester] (mapv :action/requester records)))
      (is (= "dashboard" (:principal requester)))
      (is (= :operator (:role requester)))
      (is (uuid? (:listener-id requester)))
      (is (= :control (:listener/capability listener)))
      (is (= (:principal requester) (get-in listener [:listener/identity :principal])))
      (is (= justification (:action/justification record)))
      (is (= {:target-type :workflow :target-id workflow-id} (:action/target record)))
      (is (= parameters (:action/parameters record)))
      (is (= [:success :success] (mapv #(get-in % [:action/result :status]) records))))))

(deftest ^{:stratum 2} failed-interventions-stay-failed-in-http-and-evidence
  (doseq [intervention [fail-intervention
                       (constantly (anomaly/anomaly :fault failure-message {}))
                       (constantly (response/make-anomaly :anomalies/fault failure-message {}))
                       (constantly (response/failure failure-message))]]
    (let [stream (events/create-event-stream {:sinks []})
          dashboard-state (dashboard stream)
          workflow-id (random-uuid)]
      (with-redefs [events/request-intervention! intervention]
        (let [http (handlers/handle-api-workflow-command-v2 dashboard-state workflow-id (control-body))
              body (json/parse-string (:body http) true)
              records (recorded-actions stream workflow-id)
              result (:action/result (first records))]
          (is (= 500 (:status http)))
          (is (= "failed" (:status body)))
          (is (= (messages/t :control/execution-failed) (get-in body [:result :error :message])))
          (is (not (str/includes? (:body http) failure-message)))
          (is (= 1 (count records)))
          (is (= :failure (:status result)))
          (is (response/error? result))
          (is (str/includes? (get-in result [:error :message]) failure-message)))))))

(deftest ^{:stratum 2} interrupted-interventions-retain-the-worker-signal
  (let [dashboard-state (dashboard (events/create-event-stream {:sinks []}))]
    (with-redefs [events/request-intervention! (fn [_] (throw (InterruptedException. failure-message)))]
      (let [worker (future
                     (try
                       (let [http (handlers/handle-api-workflow-command-v2
                                    dashboard-state (random-uuid) (control-body))]
                         [(:status http) (.isInterrupted (Thread/currentThread))])
                       (finally (Thread/interrupted))))]
        (is (= [500 true] (deref worker wait-ms :timeout)))))))
