;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.terminal-receipt-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.workflow.phase-event-test-support :as journal]
            [ai.miniforge.workflow.runner-events :as publishers]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} context []
  {:execution/id (random-uuid)
   :execution/status :completed
   :execution/metrics {:duration-ms 10}
   :workflow/evidence-bundle-id (random-uuid)
   :workflow/pr-info {:pr-number 42
                      :pr-url "https://github.com/acme/widget/pull/42"
                      :branch "feature/widget"}})

(defn- ^{:stratum 0} constructor-exception [& _args]
  (throw (IllegalStateException. "Constructor unavailable")))

(defn- ^{:stratum 0} constructor-fatal [& _args]
  (throw (AssertionError. "Fatal constructor failure")))

(defn- ^{:stratum 0} observe-notification [calls constructor & args]
  (swap! calls inc)
  (apply constructor args))

(defn- ^{:stratum 0} verify-completion [ctx expected-types stream options]
  (let [receipt (publishers/publish-workflow-completed! stream ctx)
        history (events/get-events stream)]
    (is (= :workflow/completed (:event/type receipt)))
    (is (= receipt (first history)))
    (is (= expected-types (mapv :event/type history)))
    (events/close-current-event-stream! stream)
    (is (= history (journal/reopened-events options)))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-notification-exception [stream options]
  (with-redefs [events/pr-created constructor-exception]
    (verify-completion (context) [:workflow/completed] stream options)))

(defn- ^{:stratum 1} verify-constructor-exception [status stream _options]
  (with-redefs [events/workflow-completed constructor-exception
                events/workflow-failed constructor-exception]
    (is (anomaly/any-anomaly?
          (publishers/publish-workflow-completed! stream (assoc (context) :execution/status status))))
    (is (empty? (events/get-events stream)))))

(defn- ^{:stratum 1} verify-refusal [stream _options]
  (let [refused (assoc (context) :execution/status :running)
        calls (atom 0)
        constructor events/pr-created]
    (with-redefs [events/pr-created (partial observe-notification calls constructor)]
      (is (anomaly/any-anomaly? (publishers/publish-workflow-completed! stream refused)))
      (is (zero? @calls))
      (is (empty? (events/get-events stream))))))

(defn- ^{:stratum 1} verify-fatal [stream _options]
  (with-redefs [events/workflow-completed constructor-fatal]
    (is (thrown? AssertionError (publishers/publish-workflow-completed! stream (context))))))

(deftest ^{:stratum 1} current-success-retains-receipt-when-legacy-pr-notification-is-refused
  (let [calls (atom 0)
        constructor events/pr-created]
    (with-redefs [events/pr-created (partial observe-notification calls constructor)]
      (journal/with-stream (partial verify-completion (context) [:workflow/completed]))
      (is (= 1 @calls)))))

(deftest ^{:stratum 1} nil-and-legacy-constructor-failure-remain-no-op-and-warning-only
  (is (nil? (publishers/publish-workflow-completed! nil (context))))
  (with-redefs [events/workflow-completed constructor-exception]
    (is (nil? (publishers/publish-workflow-completed!
                (events/create-event-stream {:sinks []}) (context))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} notification-exception-does-not-replace-durable-terminal-receipt
  (journal/with-stream verify-notification-exception))

(deftest ^{:stratum 2} terminal-constructor-failure-is-a-current-refusal
  (doseq [status [:completed :failed]]
    (journal/with-stream (partial verify-constructor-exception status))))

(deftest ^{:stratum 2} refused-terminal-does-not-publish-pr-notifications
  (journal/with-stream verify-refusal))

(deftest ^{:stratum 2} fatal-terminal-errors-propagate
  (journal/with-stream verify-fatal))
