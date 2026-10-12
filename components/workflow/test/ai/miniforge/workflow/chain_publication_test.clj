;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.chain-publication-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.workflow.chain-execution-test-support :as support]
            [ai.miniforge.workflow.chain-publication :as publication]
            [ai.miniforge.workflow.interface :as workflow]
            [ai.miniforge.workflow.loader :as loader]
            [ai.miniforge.workflow.runner :as runner]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} execute [calls status _definition _input opts]
  (swap! calls conj (:workflow-id opts))
  {:execution/id (:workflow-id opts) :execution/status status})

(defn- ^{:stratum 0} emit [attempts fail-type failure _stream event]
  (swap! attempts conj event)
  (if (= fail-type (:event/type event)) failure event))

(def ^{:stratum 0} ^:private completed-events
  [:chain/started :chain/step-started :chain/step-completed
   :chain/step-started :chain/step-completed :chain/completed])

(deftest ^{:stratum 0} real-quiesced-child-publication-is-a-failure
  (let [stream (events/create-event-stream {:sinks []}) id (random-uuid)]
    (events/quiesce! stream {:workflow-id id})
    (is (= :unavailable
           (:anomaly/type (publication/emit-step! {:event-stream stream :workflow-id id}
                                                  events/chain-step-started :chain :only 0 :workflow))))
    (is (empty? (events/get-events stream)))))

(deftest ^{:stratum 0} missing-or-mismatched-receipts-are-not-acknowledgments
  (let [stream (events/create-event-stream {:sinks []})
        event (events/chain-started stream :chain 1)]
    (doseq [receipt [nil {} {:rejected? true} (assoc event :event/id (random-uuid))
                     (dissoc event :event/sequence-number) (assoc event :event/type :chain/completed)
                     (assoc event :event/version "unexpected")]]
      (with-redefs [events/publish! (constantly receipt)]
        (is (= :unavailable (:anomaly/type (publication/emit! {:event-stream stream} (constantly event)))))))))

(deftest ^{:stratum 0} failed-construction-never-reaches-publication
  (let [calls (atom []) failure (anomaly/anomaly :invalid-input "Constructor refused" {})]
    (with-redefs [events/publish! (fn [& args] (swap! calls conj args))]
      (is (= failure (publication/emit! {:event-stream :unused} (constantly failure))))
      (is (empty? @calls)))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} redacted-failure-receipts-remain-valid
  (let [stream (events/create-event-stream {:sinks []}) calls (atom [])
        failing (comp #(assoc % :execution/error "Bearer abcdefghijklmnopqrstuvwxyz0123456789")
                       (partial execute calls :failed))]
    (with-redefs [loader/load-workflow support/loaded-workflow runner/run-pipeline failing]
      (is (= :failed (:chain/status (workflow/run-chain (support/definition) {} {:event-stream stream}))))
      (is (= 1 (count @calls))))))

(defn- ^{:stratum 1} observed [fail-type failure status]
  (let [calls (atom []) attempts (atom []) stream (events/create-event-stream {:sinks []})]
    (with-redefs [loader/load-workflow support/loaded-workflow
                  runner/run-pipeline (partial execute calls status)
                  events/publish! (partial emit attempts fail-type failure)]
      {:result (workflow/run-chain (support/definition) {} {:event-stream stream})
       :calls @calls :attempts @attempts})))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} each-publication-failure-stops-dependent-execution
  (let [failure (anomaly/anomaly :unavailable "Publication failed" {})]
    (doseq [[type expected-count expected]
            [[:chain/started 0 [:chain/started]]
             [:chain/step-started 0 [:chain/started :chain/step-started :chain/failed]]
             [:chain/step-completed 1 [:chain/started :chain/step-started :chain/step-completed :chain/failed]]
             [:chain/completed 2 completed-events]]]
      (let [{:keys [result calls attempts]} (observed type failure :completed)]
        (is (= failure result))
        (is (= expected-count (count calls)))
        (is (= expected (mapv :event/type attempts)))))))

(deftest ^{:stratum 2} failure-path-publication-cannot-mask-a-missing-event
  (doseq [type [:chain/step-failed :chain/failed]]
    (let [failure (anomaly/anomaly :unavailable "Failure event unavailable" {})
          {:keys [result calls]} (observed type failure :failed)]
      (is (= failure result))
      (is (= 1 (count calls))))))

(deftest ^{:stratum 2} successful-publication-preserves-child-identities
  (let [{:keys [result calls attempts]} (observed nil nil :completed)
        starts (filter #(= :chain/step-started (:event/type %)) attempts)]
    (is (= :completed (:chain/status result)))
    (is (= completed-events (mapv :event/type attempts)))
    (is (= calls (mapv :workflow/id starts)))
    (is (= 2 (count (set calls))))))

(deftest ^{:stratum 2} legacy-anomalies-and-rejections-stop-the-chain
  (doseq [failure [{:anomaly/category :anomalies/unavailable}
                   {:rejected? true :reason :workflow-quiesced}]]
    (let [{:keys [result calls]} (observed :chain/started failure :completed)]
      (is (anomaly/any-anomaly? result))
      (is (empty? calls)))))

(comment
  ::fail-closed-chain-publication)
