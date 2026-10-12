;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.current-audit-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.phase-opsv.event-delivery :as delivery]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.pr-audit :as audit]
            [ai.miniforge.phase-opsv.pr-fixtures :as fixture]
            [ai.miniforge.response.interface :as response]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} with-current-audit [operation]
  (fs/with-temp-dir [directory {:prefix "opsv-current-audit-"}]
    (let [{:keys [ctx calls]} (fixture/setup)
          stream (events/create-current-event-stream {:journal-directory (str (fs/real-path directory))})]
      (is (not (anomaly/anomaly? stream)) (pr-str stream))
      (when-not (anomaly/anomaly? stream)
        (try+ (operation (assoc ctx :event-stream stream) calls stream)
          (finally (events/close-current-event-stream! stream)))))))

(defn- ^{:stratum 0} invalid-receipt [mode _ draft]
  (case mode
    :unpositioned draft
    :wrong-workflow (assoc draft :event/sequence-number 0 :workflow/id (random-uuid))))

(defn- ^{:stratum 0} refuse-terminal [publish stream draft]
  (if (= :succeeded (:opsv/effect-state draft))
    (anomaly/anomaly :unavailable "fixture terminal audit unavailable" {})
    (publish stream draft)))

(defn- ^{:stratum 0} verify-indexed-retry [ctx calls stream]
  (let [queries (atom [])
        query (partial swap! queries conj)]
    (with-redefs [events/get-events query]
      (let [output (phase/actuate ctx)
            transaction (first (:opsv/effect-transactions output))]
        (is (not (anomaly/anomaly? output)))
        (is (= transaction (audit/record! ctx transaction)))))
    (is (empty? @queries))
    (is (= 2 (count (events/get-events stream))))
    (is (= 2 (count @calls)))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} governed-mutation-uses-durable-dispositions-and-reuses-receipts
  (with-current-audit
    (fn [ctx calls stream]
      (let [output (phase/actuate ctx)
            transaction (first (:opsv/effect-transactions output))
            records (events/get-events stream)]
        (is (not (anomaly/anomaly? output)))
        (is (= 2 (count @calls)))
        (is (= [:proposed :succeeded] (mapv :opsv/effect-state records)))
        (is (= (range (count records)) (map :event/sequence-number records)))
        (is (= transaction (audit/record! ctx transaction)))
        (is (= records (events/get-events stream)))
        (let [unavailable (anomaly/anomaly :unavailable "fixture journal unavailable" {})]
          (with-redefs [events/get-events (constantly []) events/publish! (constantly unavailable)]
            (is (anomaly/anomaly? (audit/record! ctx transaction)))))
        (events/close-current-event-stream! stream)
        (is (anomaly/anomaly? (audit/record! ctx transaction)))
        (is (= 2 (count @calls)))))))

(deftest ^{:stratum 1} missing-or-substituted-receipts-block-provider-mutation
  (doseq [mode [:unpositioned :wrong-workflow]]
    (with-current-audit
      (fn [ctx calls stream]
        (with-redefs [events/publish! (partial invalid-receipt mode)]
          (is (anomaly/anomaly? (phase/actuate ctx))))
        (is (empty? @calls))
        (is (empty? (events/get-events stream)))))))

(deftest ^{:stratum 1} closed-current-stream-cannot-fall-back-to-legacy-audit
  (with-current-audit
    (fn [ctx calls stream]
      (events/close-current-event-stream! stream)
      (is (anomaly/anomaly? (phase/actuate ctx)))
      (is (empty? @calls))
      (is (empty? (events/get-events stream))))))

(deftest ^{:stratum 1} terminal-publication-failure-retains-outcome-for-audit-only-retry
  (with-current-audit
    (fn [ctx calls stream]
      (let [publish (partial refuse-terminal events/publish!)
            failed (with-redefs [events/publish! publish] (phase/actuate ctx))
            transaction (get-in failed [:anomaly/data :effect/transaction])]
        (is (anomaly/anomaly? failed))
        (is (= :succeeded (:effect/state transaction)))
        (is (= 2 (count @calls)))
        (is (= [:proposed] (mapv :opsv/effect-state (events/get-events stream))))
        (is (= transaction (audit/record! ctx transaction)))
        (is (= [:proposed :succeeded] (mapv :opsv/effect-state (events/get-events stream))))
        (is (= 2 (count @calls)))))))

(deftest ^{:stratum 1} valid-current-events-from-other-families-are-not-opsv-audits
  (with-current-audit
    (fn [ctx calls stream]
      (let [event (merge (events/create-event-draft stream :chain/started nil "fixture chain")
                         {:event/version "2.0.0" :scope/type :chain :chain/run-id (random-uuid)
                          :chain/definition-id :fixture :chain/definition-version "1.0.0" :chain/step-count 0})]
        (is (vector? (events/prepare-current-publication event)))
        (is (anomaly/anomaly? (delivery/emit! ctx stream event)))
        (is (empty? (events/get-events stream)))
        (is (empty? @calls))))))

(deftest ^{:stratum 1} either-identity-failure-format-blocks-phase-mutation
  (doseq [failure [(anomaly/anomaly :unavailable "fixture identity unavailable" {})
                  (response/make-anomaly :anomalies/unavailable "fixture legacy identity unavailable" {})]]
    (with-current-audit
      (fn [ctx calls stream]
        (swap! stream assoc :snowflake-generator failure)
        (is (anomaly/anomaly? (phase/actuate ctx)))
        (is (empty? @calls))
        (is (empty? (events/get-events stream)))))))

(deftest ^{:stratum 1} current-publication-and-retry-do-not-query-history
  (with-current-audit verify-indexed-retry))

(comment
  ::durable-opsv-audit)
