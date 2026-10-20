;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under Apache 2.0.
(ns ai.miniforge.event-stream.workflow-terminal-constructor-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.event-stream.messages :as msg]
            [ai.miniforge.event-stream.snowflake :as snowflake]
            [ai.miniforge.event-stream.workflow-draft-fixtures :as fixture]
            [ai.miniforge.response.interface :as response]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} completed [stream workflow-id]
  (events/workflow-completed stream workflow-id :completed 0
                             {:workflow/evidence-bundle-id (random-uuid) :tokens 0 :cost-usd 0.0}))

(defn- ^{:stratum 0} failed
  ([stream workflow-id] (failed stream workflow-id (Exception. "fixture failure")))
  ([stream workflow-id error]
   (events/workflow-failed stream workflow-id error
                           {:failure/class :failure.class/task-code :workflow/failure-phase :opsv/verify})))

(defn- ^{:stratum 0} error-cases []
  [{:message "fixture failure" :type :fixture}
   (Exception. "fixture failure")
   (response/make-anomaly :anomalies/fault "fixture failure")])

(defn- ^{:stratum 0} verify-reopened [options receipt]
  (let [stream (events/create-current-event-stream options)]
    (is (not (anomaly/anomaly? stream)))
    (when-not (anomaly/anomaly? stream)
      (try+ (is (= [receipt] (events/get-events stream)))
        (finally (events/close-current-event-stream! stream))))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-current [constructor expected stream options]
  (let [draft (constructor stream (random-uuid))
        receipt (events/publish! stream draft)]
    (is (not (contains? draft :event/sequence-number)))
    (is (not (contains? draft :workflow/status)))
    (is (= expected (select-keys draft (keys expected))))
    (when (contains? (:workflow/error-details draft) :retryable?)
      (is (= (:workflow/retryable? draft) (get-in draft [:workflow/error-details :retryable?]))))
    (is (vector? (events/prepare-current-publication draft)))
    (is (= 0 (:event/sequence-number receipt)))
    (is (= receipt (events/publish! stream draft)))
    (is (= [receipt] (events/get-events stream)))
    (events/close-current-event-stream! stream)
    (is (anomaly/anomaly? (events/publish! stream draft)))
    (verify-reopened options receipt)))

(defn- ^{:stratum 1} verify-refusals [stream _]
  (let [workflow-id (random-uuid)]
    (doseq [status [nil :success :failed :cancelled]]
      (is (anomaly/anomaly? (events/workflow-completed stream workflow-id status))))
    (doseq [draft [(events/workflow-completed stream workflow-id :completed)
                   (events/workflow-completed stream workflow-id :completed 0)
                   (assoc (completed stream workflow-id) :workflow/duration-ms -1)
                   (events/workflow-failed stream workflow-id {:message "fixture failure"})
                   (assoc (failed stream workflow-id) :failure/class :unknown)]]
      (is (anomaly/anomaly? (events/publish! stream draft))))
    (is (empty? (events/get-events stream)))
    (is (= 0 (:event/sequence-number (events/publish! stream (completed stream workflow-id)))))))

(defn- ^{:stratum 1} verify-identity-failure [stream _]
  (let [failure (anomaly/anomaly :unavailable "fixture identity allocation failed" {})]
    (swap! stream assoc :snowflake-generator :fixture)
    (with-redefs [snowflake/next-id! (constantly failure)]
      (is (= failure (completed stream (random-uuid))))
      (is (= failure (failed stream (random-uuid)))))))

(deftest ^{:stratum 1} legacy-terminal-payloads-preserve-status-and-diagnostics
  (let [stream (events/create-event-stream {:sinks []})
        workflow-id (random-uuid)
        event (completed stream workflow-id)]
    (is (= :completed (:workflow/status event)))
    (is (= 0 (:workflow/duration-ms event)))
    (is (= 0 (:workflow/tokens event)))
    (is (= 0.0 (:workflow/cost-usd event)))
    (is (uuid? (:workflow/evidence-bundle-id event)))
    (doseq [error (error-cases)]
      (let [event (events/workflow-failed stream workflow-id error)]
        (is (= "fixture failure" (:workflow/failure-reason event)))
        (is (= (msg/t :workflow/failed {:reason "fixture failure"}) (:message event)))
        (is (map? (:workflow/error-details event)))
        (is (= :anomalies/fault (:workflow/anomaly-code event)))
        (is (false? (:workflow/retryable? event)))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} current-terminal-facts-commit-and-replay
  (doseq [[constructor expected]
          [[completed {:workflow/duration-ms 0 :workflow/tokens 0 :workflow/cost-usd 0.0}]
           [failed {:failure/class :failure.class/task-code
                    :workflow/failure-phase :opsv/verify :workflow/failure-reason "fixture failure"}]]]
    (fixture/with-stream (partial verify-current constructor expected))))

(deftest ^{:stratum 2} incomplete-and-contradictory-terminal-facts-fail-closed
  (fixture/with-stream verify-refusals))

(deftest ^{:stratum 2} terminal-diagnostics-preserve-canonical-and-message-less-failures
  (doseq [[error expected]
          (concat
           [[(Exception.) {:workflow/failure-reason (str Exception)
                          :workflow/anomaly-code :anomalies/fault :workflow/retryable? false}]
           [(anomaly/sub-anomaly :unavailable :anomalies.llm/timeout "fixture timeout" {})
            {:workflow/failure-reason "fixture timeout"
             :workflow/anomaly-code :anomalies.llm/timeout :workflow/retryable? true}]
           [(response/make-anomaly :anomalies.llm/timeout "fixture timeout")
            {:workflow/failure-reason "fixture timeout"
             :workflow/anomaly-code :anomalies.llm/timeout :workflow/retryable? true}]
           [(anomaly/anomaly :fault "fixture failure" {})
            {:workflow/failure-reason "fixture failure"
             :workflow/anomaly-code :fault :workflow/retryable? false}]]
           (for [type [:unavailable :timeout]]
             [(anomaly/anomaly type "fixture transient" {})
              {:workflow/failure-reason "fixture transient"
               :workflow/anomaly-code type :workflow/retryable? true}])
           (for [code [:anomalies/busy :anomalies/interrupted]]
             [(response/make-anomaly code "fixture transient")
              {:workflow/failure-reason "fixture transient"
               :workflow/anomaly-code code :workflow/retryable? true}]))]
    (let [constructor (fn [stream workflow-id] (failed stream workflow-id error))
          legacy (constructor (events/create-event-stream {:sinks []}) (random-uuid))]
      (is (= expected (select-keys legacy (keys expected))))
      (fixture/with-stream (partial verify-current constructor expected)))))

(deftest ^{:stratum 2} identity-failures-remain-anomalies-on-both-profiles
  (fixture/with-stream verify-identity-failure)
  (verify-identity-failure (events/create-event-stream {:sinks []}) nil))

(comment ::workflow-terminal-constructor-acceptance)
