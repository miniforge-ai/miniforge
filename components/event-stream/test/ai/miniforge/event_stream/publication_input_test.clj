;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.publication-input-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.chain-test-support :as chain]
            [ai.miniforge.event-stream.commit-test-support :as support]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.event-stream.supervisory-test-support :as supervisory]
            [ai.miniforge.redaction.interface :as redaction]
            [ai.miniforge.response.interface :as response]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [try+ throw+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} draft [payload]
  (merge (support/draft) payload))

(defn- ^{:stratum 0} observe-redaction [calls event]
  (swap! calls inc)
  event)

(defn- ^{:stratum 0} throw-redaction [_]
  (throw (ex-info "redaction fixture failure" {})))

(defn- ^{:stratum 0} throw-value [value _] (throw+ value))

(defn- ^{:stratum 0} constructed-chain-draft [event-type]
  (let [stream (events/create-event-stream {:sinks []})
        fields (dissoc (chain/payload event-type) :event/type :event/version :scope/type)]
    (events/create-chain-event-draft stream event-type fields)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} chain-draft []
  (draft (chain/payload :chain/started)))

(defn- ^{:stratum 1} supported-drafts []
  (concat (map constructed-chain-draft (keys chain/required-fields))
          (map draft [(supervisory/request) (supervisory/change) (supervisory/snapshot)])))

(deftest ^{:stratum 1} supervisory-payloads-cannot-fall-back-to-generic-envelopes
  (doseq [payload [(dissoc (supervisory/request) :intervention/justification)
                   (assoc (supervisory/request) :intervention/state :approved)
                   (assoc (supervisory/change) :supervisory/entity-key (random-uuid))
                   (dissoc (supervisory/change) :intervention/from-state)
                   (assoc (supervisory/change) :event/version "1.0.0")
                   (assoc (supervisory/snapshot) :event/version "99.0.0")
                   (update (supervisory/snapshot) :supervisory/entity dissoc :spec/origin)
                   (assoc (supervisory/snapshot) :supervisory/schema-version 2)]]
    (is (anomaly/anomaly? (events/prepare-current-publication (draft payload))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} supported-profiles-select-exact-scope-without-changing-clean-drafts
  (doseq [event (supported-drafts)]
    (let [expected (if (:chain/run-id event) [:chain (:chain/run-id event)]
                       [:supervisory-entity (:supervisory/entity-key event)])]
      (is (= [expected event] (events/prepare-current-publication event)))
      (is (= expected (first (events/prepare-current-publication
                              (assoc event :workflow/id (random-uuid)))))))))

(deftest ^{:stratum 2} invalid-drafts-are-rejected-before-redaction
  (let [calls (atom 0)
        event (chain-draft)]
    (with-redefs [redaction/redact (partial observe-redaction calls)]
      (doseq [bad [nil [] {} (support/draft) (assoc event :event/type :chain/unknown)
                   (assoc event :event/version "1.0.0") (assoc event :event/sequence-number 0)
                   (assoc event :timestamp nil) (assoc event :scope/type :workflow)
                   (assoc event :chain/id nil)
                   (assoc event :chain/step-count -1)]]
        (is (anomaly/anomaly? (events/prepare-current-publication bad))))
      (doseq [key [:event/id :event/type :event/timestamp :event/version :message
                   :chain/run-id :chain/definition-id :chain/definition-version :chain/step-count]]
        (is (anomaly/anomaly? (events/prepare-current-publication (dissoc event key)))))
      (doseq [key [:event/parent-id :agent/instance-id :workflow/id :pr/id :org/id :workspace/id]]
        (is (anomaly/anomaly? (events/prepare-current-publication (assoc event key :invalid)))))
      (is (zero? @calls)))))

(deftest ^{:stratum 2} malformed-identities-are-not-echoed-into-errors
  (let [event (assoc (chain-draft) :event/id "AKIAIOSFODNN7EXAMPLE")
        result (events/prepare-current-publication event)]
    (is (anomaly/anomaly? result))
    (is (nil? (get-in result [:anomaly/data :event/id])))
    (is (redaction/clean? result))))

(deftest ^{:stratum 2} redaction-removes-sensitive-content-but-cannot-rewrite-identity
  (let [event (assoc (chain-draft) :password "fixture-secret")
        result (events/prepare-current-publication event)]
    (is (= (redaction/marker) (:password (second result))))
    (is (= (:event/id event) (:event/id (second result))))
    (doseq [field [:chain/definition-version :pack/id :deployment/id :repo/id]]
      (is (anomaly/anomaly? (events/prepare-current-publication
                             (assoc event field "AKIAIOSFODNN7EXAMPLE")))))
    (doseq [changed [(assoc event :chain/run-id (random-uuid))
                     (assoc event :event/id (random-uuid))
                     (assoc event :pr/id (random-uuid))
                     (assoc event :chain/definition-version "rewritten")
                     (dissoc event :message)]]
      (with-redefs [redaction/redact (constantly changed)]
        (is (anomaly/anomaly? (events/prepare-current-publication event)))))
    (with-redefs [redaction/redact throw-redaction]
      (is (anomaly/anomaly? (events/prepare-current-publication event))))))

(deftest ^{:stratum 2} upstream-and-redaction-anomalies-remain-values
  (doseq [failure [(anomaly/anomaly :fault (:message (support/draft)) {})
                   (response/make-anomaly :anomalies/fault (:message (support/draft)) {})]]
    (is (identical? failure (events/prepare-current-publication failure)))
    (with-redefs [redaction/redact (constantly failure)]
      (is (identical? failure (events/prepare-current-publication (chain-draft)))))
    (with-redefs [redaction/redact (partial throw-value failure)]
      (is (identical? failure (events/prepare-current-publication (chain-draft)))))))

(deftest ^{:stratum 2} critical-redaction-causes-are-not-downgraded
  (doseq [cause [(AssertionError.) (InterruptedException.)]]
    (let [wrapped (ex-info "wrapped redaction fixture" {} cause)]
      (with-redefs [redaction/redact (partial throw-value wrapped)]
        (try+
          (events/prepare-current-publication (chain-draft))
          (is false "Critical cause must escape admission")
          (catch Throwable actual (is (identical? cause actual)))
          (finally (Thread/interrupted)))))))

(deftest ^{:stratum 2} opaque-nested-values-are-rejected-before-redaction
  (let [calls (atom 0)
        event (chain-draft)
        secret "AKIAIOSFODNN7EXAMPLE"]
    (with-redefs [redaction/redact (partial observe-redaction calls)]
      (doseq [opaque [(atom secret) (object-array [secret])
                     (java.util.concurrent.atomic.AtomicLong. 4111111111111111)
                     (java.util.concurrent.atomic.AtomicInteger. 42)
                     (java.util.ArrayList. [secret]) (java.util.HashMap. {:value secret})
                     (delay secret) (map identity [secret])]
              nested [[opaque] {opaque :value} (with-meta [] {:nested opaque})
                      (with-meta 'field {:nested opaque})]]
        (let [result (events/prepare-current-publication (assoc event :extension/data nested))]
          (is (anomaly/anomaly? result))
          (is (not (.contains (pr-str result) secret)))))
      (is (zero? @calls)))))

(deftest ^{:stratum 2} finite-extension-values-are-redacted-through-keys-and-metadata
  (let [secret "AKIAIOSFODNN7EXAMPLE"
        extension (with-meta {secret (list secret)} {:nested secret})
        event (assoc (chain-draft) :extension/data extension)
        result (events/prepare-current-publication event)]
    (is (vector? result))
    (is (redaction/clean? (second result)))
    (is (= (list (redaction/marker))
           (get-in (second result) [:extension/data (redaction/marker)])))))

(deftest ^{:stratum 2} admitted-numeric-card-representations-are-redacted
  (doseq [card [4111111111111111M 4111111111111111.0]
          extension [card {card :value} (with-meta [] {:value card})]]
    (let [event (assoc (chain-draft) :extension/data extension)
          result (events/prepare-current-publication event)]
      (is (vector? result))
      (is (not (redaction/payment-card? (second result))))
      (is (redaction/payment-card? event)))))

(comment
  (supported-drafts))
