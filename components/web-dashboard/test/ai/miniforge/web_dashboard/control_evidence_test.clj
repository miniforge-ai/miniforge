;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.web-dashboard.control-evidence-test
  (:require [clojure.test :refer [deftest is]]
            [cheshire.core :as json]
            [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.web-dashboard.server :as server]
            [ai.miniforge.web-dashboard.server.handlers :as handlers]
            [ai.miniforge.web-dashboard.state.core :as state]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} dashboard [stream]
  (state/create-state {:event-stream stream}))

(defn- ^{:stratum 0} registered-listener [stream listener-id]
  (first (filter #(= listener-id (:listener/id %)) (events/list-listeners stream))))

(defn- ^{:stratum 0} control-body []
  (json/generate-string
   {:action/type :pause
    :action/requester {:principal "spoofed" :role :admin :listener-id (random-uuid)}}))

(defn- ^{:stratum 0} recorded-actions [stream workflow-id]
  (:evidence/control-actions
   (evidence/assemble-evidence-bundle workflow-id {:workflow/status :completed}
                                      nil {:event-stream stream})))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} dashboard-controls-retain-registered-identity-in-evidence
  (let [stream (events/create-event-stream {:sinks []})
        dashboard-state (dashboard stream)
        requester (:control/requester @dashboard-state)
        workflow-id (random-uuid)]
    (with-redefs [events/request-intervention! identity]
      (dotimes [_ 2]
        (is (= 200 (:status (handlers/handle-api-workflow-command-v2
                            dashboard-state workflow-id (control-body)))))))
    (let [records (recorded-actions stream workflow-id)
          listener (registered-listener stream (:listener-id requester))]
      (is (= 2 (count records)))
      (is (= [requester requester] (mapv :action/requester records)))
      (is (= "dashboard" (:principal requester)))
      (is (= :operator (:role requester)))
      (is (uuid? (:listener-id requester)))
      (is (= :control (:listener/capability listener)))
      (is (= (:principal requester) (get-in listener [:listener/identity :principal])))
      (is (= [:success :success] (mapv #(get-in % [:action/result :status]) records))))))

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
