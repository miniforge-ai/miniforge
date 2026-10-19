;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.phase-agent-event-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.workflow.phase-event-test-support :as support]
            [ai.miniforge.workflow.runner-callbacks :as callbacks]
            [ai.miniforge.workflow.runner-events :as runner-events]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} context []
  {:execution/id (random-uuid)
   :execution/workflow {:workflow/id :agent-facts :workflow/version "1.0.0"}
   :execution/phase-index 0})

(defn- ^{:stratum 0} interceptor [config]
  {:config (assoc config :phase :verify)})

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-executing-interceptor [stream options]
  (let [ctx (context)
        wrapped (callbacks/wrap-phase-callbacks stream {})
        start (:on-phase-start wrapped)
        configs [{:agent :reviewer}
                 {:agent :tester}
                 {:agent nil :phase/expected-agent :agent/none}
                 {:agent :implementer :phase/expected-agent :agent/none}]]
    ;; Repeated phase identifiers must not select the first workflow declaration.
    (with-redefs [runner-events/start-phase-heartbeat! (constantly nil)]
      (doseq [config configs]
        (start ctx (assoc (interceptor config) :phase :stale-legacy-phase))))
    (let [history (events/get-events stream)]
      (is (= [:reviewer :tester :agent/none :implementer]
             (mapv :phase/expected-agent history)))
      (is (= [0 1 2 3] (mapv :event/sequence-number history)))
      (is (every? #(= :verify (:workflow/phase %)) history))
      (events/close-current-event-stream! stream)
      (is (= history (support/reopened-events options))))))

(defn- ^{:stratum 1} verify-missing-agent [stream _options]
  (let [ctx (context)
        wrapped (callbacks/wrap-phase-callbacks stream {})]
    (with-redefs [runner-events/start-phase-heartbeat! (constantly nil)]
      (doseq [config [{} {:agent nil} {:agent false :phase/expected-agent :agent/none}]]
        ((:on-phase-start wrapped) ctx (interceptor config))))
    (is (empty? (events/get-events stream)))
    (is (anomaly/any-anomaly?
          (runner-events/publish-phase-started! stream ctx :verify)))
    (is (= 0 (:event/sequence-number
               (runner-events/publish-phase-started! stream ctx :verify
                                                     {:phase/expected-agent :reviewer}))))))

(deftest ^{:stratum 1} legacy-phase-entry-call-remains-supported
  (let [stream (events/create-event-stream {:sinks []})]
    (runner-events/publish-phase-started! stream (context) :verify)
    (is (= :workflow/phase-started (:event/type (first (events/get-events stream)))))
    (is (not (contains? (first (events/get-events stream)) :phase/expected-agent)))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} current-phase-entry-retains-the-executing-agent
  (support/with-stream verify-executing-interceptor))

(deftest ^{:stratum 2} missing-agent-does-not-become-agentless
  (support/with-stream verify-missing-agent))
