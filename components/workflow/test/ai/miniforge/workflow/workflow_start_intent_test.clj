;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.workflow-start-intent-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.workflow.phase-event-test-support :as journal]
            [ai.miniforge.workflow.runner-events :as publishers]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} constructor-exception [& _args]
  (throw (IllegalStateException. "Start constructor unavailable")))

(defn- ^{:stratum 0} constructor-fatal [& _args]
  (throw (AssertionError. "Fatal start constructor failure")))

(defn- ^{:stratum 0} context [opts]
  {:execution/id (random-uuid)
   :execution/workflow {:workflow/id :start-intent :workflow/version "1.0.0"}
   :execution/input {:intent "Free-form request"}
   :execution/opts opts
   :routing/trigger-event-id (random-uuid)})

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-construction-exception [stream _options]
  (with-redefs [events/workflow-started constructor-exception]
    (is (anomaly/any-anomaly? (publishers/publish-workflow-started! stream (context {}))))
    (is (empty? (events/get-events stream)))))

(defn- ^{:stratum 1} verify-fatal-construction [stream _options]
  (with-redefs [events/workflow-started constructor-fatal]
    (is (thrown? AssertionError (publishers/publish-workflow-started! stream (context {}))))))

(deftest ^{:stratum 1} legacy-constructor-exception-retains-warning-only-behavior
  (with-redefs [events/workflow-started constructor-exception]
    (is (nil? (publishers/publish-workflow-started!
                (events/create-event-stream {:sinks []}) (context {}))))))

(defn- ^{:stratum 1} verify-start [stream options]
  (let [intent {:intent/operation :verify :intent/target :operational-policy}
        ctx (context {:workflow/intent intent :private/runtime-option :not-event-data})
        receipt (publishers/publish-workflow-started! stream ctx)
        history (events/get-events stream)]
    (is (= :workflow/started (:event/type receipt)))
    (is (= intent (:workflow/intent receipt)))
    (is (= (:execution/id ctx) (:workflow/id receipt)))
    (is (= (:execution/workflow ctx) (:workflow/spec receipt)))
    (is (= (:routing/trigger-event-id ctx) (:routing/trigger-event-id receipt)))
    (is (not (contains? receipt :private/runtime-option)))
    (is (= [receipt] history))
    (events/close-current-event-stream! stream)
    (is (= history (journal/reopened-events options)))))

(defn- ^{:stratum 1} verify-refused [opts stream _options]
  (is (anomaly/any-anomaly? (publishers/publish-workflow-started! stream (context opts))))
  (is (empty? (events/get-events stream))))

(deftest ^{:stratum 1} legacy-and-observer-only-callers-do-not-require-current-intent
  (let [ctx (context {})
        stream (events/create-event-stream {:sinks []})]
    (is (nil? (publishers/publish-workflow-started! nil ctx)))
    (is (= :workflow/started (:event/type (publishers/publish-workflow-started! stream ctx))))
    (is (= 1 (count (events/get-events stream))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} current-constructor-exception-is-returned-as-anomaly
  (journal/with-stream verify-construction-exception))

(deftest ^{:stratum 2} fatal-constructor-errors-are-not-converted
  (journal/with-stream verify-fatal-construction))

(deftest ^{:stratum 2} caller-declared-intent-reaches-the-current-journal
  (journal/with-stream verify-start))

(deftest ^{:stratum 2} missing-or-invalid-intent-is-not-inferred-from-free-form-input
  (doseq [opts [{} {:workflow/intent nil} {:workflow/intent "not an intent map"}]]
    (journal/with-stream (partial verify-refused opts))))
