;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.phase-agent-event-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.workflow.runner-callbacks :as callbacks]
            [ai.miniforge.workflow.runner-events :as runner-events]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} context []
  {:execution/id (random-uuid)
   :execution/workflow {:workflow/id :agent-facts :workflow/version "1.0.0"}
   :execution/phase-index 0})

(defn- ^{:stratum 0} interceptor [config]
  {:config (assoc config :phase :verify)})

(defn- ^{:stratum 0} reopened-history [options]
  (let [stream (events/create-current-event-stream options)]
    (try+ (events/get-events stream)
      (finally (events/close-current-event-stream! stream)))))

(defn- ^{:stratum 0} with-stream [verify]
  (fs/with-temp-dir [directory {:prefix "phase-agent-facts-"}]
    (let [options {:journal-directory (str (fs/canonicalize directory))}
          stream (events/create-current-event-stream options)]
      (is (events/current-event-stream? stream))
      (try+ (verify stream options)
        (finally (events/close-current-event-stream! stream))))))

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
      (doseq [config configs] (start ctx (interceptor config))))
    (let [history (events/get-events stream)]
      (is (= [:reviewer :tester :agent/none :implementer]
             (mapv :phase/expected-agent history)))
      (is (= [0 1 2 3] (mapv :event/sequence-number history)))
      (is (every? #(= :verify (:workflow/phase %)) history))
      (events/close-current-event-stream! stream)
      (is (= history (reopened-history options))))))

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
  (with-stream verify-executing-interceptor))

(deftest ^{:stratum 2} missing-agent-does-not-become-agentless
  (with-stream verify-missing-agent))
