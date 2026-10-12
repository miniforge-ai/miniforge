;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.current-publication-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.chain-test-support :as chain]
            [ai.miniforge.event-stream.commit-test-support :as support]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.event-stream.publication-store :as store]
            [ai.miniforge.response.interface :as response]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} draft [] (merge (support/draft) (chain/payload :chain/started)))

(defn- ^{:stratum 0} create [journal opts]
  (with-redefs [store/durable-store (constantly journal)]
    (events/create-current-event-stream (assoc opts :journal-directory "fixture-journal"))))

(defn- ^{:stratum 0} record! [observed event] (swap! observed conj event))

(defn- ^{:stratum 0} fail! [_] (throw (ex-info "fixture listener failure" {})))

(defn- ^{:stratum 0} mutate-date! [event]
  (.setTime ^java.util.Date (:event/timestamp event) 42))

(defn- ^{:stratum 0} committed-before-delivery! [committed observed event]
  (is (= event (last @committed)))
  (swap! observed conj event))

(defn- ^{:stratum 0} commit! [committed scope event]
  (is (= [:chain (:chain/run-id event)] scope))
  (let [receipt (assoc event :event/sequence-number 0)]
    (swap! committed conj receipt)
    receipt))

(deftest ^{:stratum 0} current-close-cannot-convert-a-legacy-stream
  (let [stream (events/create-event-stream {:sinks []})
        before @stream]
    (is (anomaly/anomaly? (events/close-current-event-stream! stream)))
    (is (= before @stream))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} creation-requires-explicit-durable-ownership
  (let [calls (atom [])]
    (with-redefs [store/durable-store (partial record! calls)]
      (doseq [opts [nil {} (sorted-map 1 :bad) {:journal-directory nil} {:journal-directory "fixture" :sinks nil}
                    {:journal-directory "fixture" :config {}}]]
        (is (anomaly/anomaly? (events/create-current-event-stream opts))))
      (is (empty? @calls)))))

(deftest ^{:stratum 1} admission-precedes-storage-and-commit-precedes-listeners
  (let [committed (atom [])
        observed (atom [])
        journal (store/ports (partial commit! committed) (constantly nil))
        stream (create journal {:sinks [fail! (partial committed-before-delivery! committed observed)]})
        event (draft)]
    (events/subscribe! stream :failed-filter identity fail!)
    (events/subscribe! stream :observer (partial record! observed))
    (is (anomaly/anomaly? (events/publish! stream (assoc event :event/sequence-number 0))))
    (is (empty? @committed))
    (let [receipt (events/publish! stream event)]
      (is (= [receipt] (events/get-events stream)))
      (is (= [receipt receipt] @observed)))))

(deftest ^{:stratum 1} retries-and-cross-references-do-not-duplicate-delivery-or-change-scope
  (let [observed (atom [])
        stream (create (store/volatile-store) {:sinks [(partial record! observed)]})
        event (assoc (draft) :workflow/id (random-uuid))
        receipt (events/publish! stream event)]
    (is (= 0 (:event/sequence-number receipt)))
    (is (= receipt (events/publish! stream event)))
    (is (= [receipt] @observed))
    (is (anomaly/anomaly? (events/publish! stream (assoc event :chain/definition-version "different"))))
    (is (= 0 (:event/sequence-number (events/publish! stream (draft)))))
    (is (= 1 (:event/sequence-number (events/publish! stream (assoc event :event/id (random-uuid))))))
    (events/close-current-event-stream! stream)
    (is (anomaly/anomaly? (events/publish! stream (draft))))))

(deftest ^{:stratum 1} storage-failure-does-not-record-or-deliver
  (let [failure (anomaly/anomaly :unavailable "fixture storage failure" {})
        journal (store/ports (constantly failure) (constantly nil))
        observed (atom [])
        stream (create journal {:sinks [(partial record! observed)]})]
    (is (identical? failure (events/publish! stream (draft))))
    (is (empty? (events/get-events stream)))
    (is (empty? @observed))))

(deftest ^{:stratum 1} public-history-queries-use-authoritative-scope
  (let [stream (create (store/volatile-store) {})
        event (assoc (draft) :workflow/id (random-uuid))
        receipt (events/publish! stream event)
        scope [:chain (:chain/run-id event)]]
    (is (= [receipt] (events/get-events stream {:scope scope})))
    (is (empty? (events/get-events stream {:workflow-id (:workflow/id event)})))
    (is (empty? (events/get-events stream {:scope scope :offset 1})))
    (is (anomaly/anomaly? (events/get-events stream {:scope nil})))
    (is (anomaly/anomaly? (events/get-events stream (sorted-map 1 :bad))))
    (is (anomaly/anomaly? (events/get-events stream {:limit -1})))))

(deftest ^{:stratum 1} legacy-barriers-cannot-claim-current-publication-is-settled
  (let [stream (create (store/volatile-store) {})]
    (doseq [operation [events/quiesce! events/drain!]]
      (is (anomaly/anomaly? (operation stream)))
      (is (anomaly/anomaly? (operation stream {:workflow-id (random-uuid)}))))
    (is (empty? (:quiesced-workflows @stream)))))

(deftest ^{:stratum 1} mutable-dates-do-not-alias-history-receipts-or-other-listeners
  (let [seen (atom [])
        stream (create (store/volatile-store) {:sinks [mutate-date! (partial record! seen)]})
        event (assoc (draft) :event/timestamp (java.util.Date. 0))
        receipt (events/publish! stream event)]
    (is (= (java.util.Date. 0) (:event/timestamp receipt)))
    (is (= [receipt] @seen))
    (doseq [copy [event receipt (first @seen) (first (events/get-events stream))]]
      (mutate-date! copy))
    (is (= (java.util.Date. 0) (:event/timestamp (first (events/get-events stream)))))))

(deftest ^{:stratum 1} recovered-records-populate-history-without-redelivery
  (let [event (assoc (draft) :event/sequence-number 4111111111111111)
        record {:scope [:chain (:chain/run-id event)] :event event}
        closed (atom [])
        journal (assoc (store/ports (constantly event) (partial record! closed :closed))
                       :recovered-records [record])
        observed (atom [])
        stream (create journal {:sinks [(partial record! observed)]})]
    (try+
      (is (= [event] (events/get-events stream)))
      (is (= event (events/publish! stream (dissoc event :event/sequence-number))))
      (is (empty? @observed))
      (finally (events/close-current-event-stream! stream)))
    (is (= [:closed] @closed))
    (doseq [bad [(assoc record :scope [:workflow (random-uuid)])
                 (assoc-in record [:event :event/version] "1.0.0")
                 (assoc-in record [:event :password] "unredacted")]]
      (is (anomaly/anomaly? (create (assoc journal :recovered-records [bad]) {}))))
    (is (= 4 (count @closed)))))

(deftest ^{:stratum 1} upstream-and-ownership-failures-remain-values
  (doseq [failure [(anomaly/anomaly :unavailable "fixture unavailable" {})
                   (response/make-anomaly :anomalies/unavailable "fixture unavailable" {})]]
    (is (identical? failure (create failure {})))
    (let [stream (create (store/volatile-store) {})]
      (is (identical? failure (events/publish! stream failure)))
      (is (empty? (events/get-events stream))))))

(comment
  (draft))
