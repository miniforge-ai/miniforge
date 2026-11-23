;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.chain-spec-test
  (:require [ai.miniforge.event-stream.chain-spec :as spec]
            [ai.miniforge.event-stream.chain-test-support :as support]
            [ai.miniforge.failure-classifier.interface :as failure]
            [clojure.test :refer [deftest is testing]]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:private true :stratum 0} required-fields support/required-fields)

(def ^{:private true :stratum 0} identity-keys
  [:event/type :event/version :scope/type :chain/run-id :chain/definition-id :chain/definition-version])

(def ^{:private true :stratum 0} numeric-keys
  [:chain/step-count :chain/duration-ms :step/index :edge/bindings-count :edge/duration-ms])

(def ^{:private true :stratum 0} uuid-keys
  [:chain/run-id :workflow/id :edge/id :edge/from-workflow-id :edge/to-workflow-id])

(def ^{:private true :stratum 0} payload support/payload)

(defn- ^{:stratum 0} valid? [payload]
  (m/validate spec/Payload payload))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} every-registered-chain-payload-has-required-fields
  (doseq [[event-type fields] required-fields]
    (testing (str event-type)
      (let [event (payload event-type)]
        (is (valid? event))
        (doseq [field (concat identity-keys fields)]
          (is (not (valid? (dissoc event field))) (str field))
          (is (not (valid? (assoc event field nil))) (str field)))))))

(deftest ^{:stratum 1} identities-cannot-be-definition-labels-or-legacy-ids
  (doseq [event-type (keys required-fields)]
    (let [event (payload event-type)]
      (doseq [field uuid-keys :when (contains? event field)
              value [:example/definition "not-a-uuid" 0]]
        (is (not (valid? (assoc event field value)))))
      (is (not (valid? (assoc event :chain/definition-id (random-uuid)))))
      (doseq [legacy-id [nil :legacy (random-uuid)]]
        (is (not (valid? (assoc event :chain/id legacy-id)))))
      (is (not (valid? (-> event (dissoc :chain/run-id) (assoc :chain/id (random-uuid)))))))))

(deftest ^{:stratum 1} current-writes-require-resolved-version-and-chain-profile
  (doseq [event-type (keys required-fields)]
    (let [event (payload event-type)]
      (doseq [version [nil "" " \n\t" "\u2003" "\u00a0" "\u3000" "\u202f" "\u0085" "latest" :latest]]
        (is (not (valid? (assoc event :chain/definition-version version)))))
      (doseq [version ["1.0.0" "2" "2.1.0" nil]]
        (is (not (valid? (assoc event :event/version version)))))
      (is (not (valid? (assoc event :scope/type :workflow)))))))

(deftest ^{:stratum 1} counts-indexes-and-durations-are-nonnegative-longs
  (doseq [event-type (keys required-fields)]
    (let [event (payload event-type)]
      (doseq [field numeric-keys :when (contains? event field)]
        (is (valid? (assoc event field 0)))
        (is (valid? (assoc event field Long/MAX_VALUE)))
        (doseq [value [-1 0.5 "0" 1N]]
          (is (not (valid? (assoc event field value)))))))))

(deftest ^{:stratum 1} failures-use-the-canonical-taxonomy
  (doseq [event-type [:chain/failed :chain/step-failed :chain.edge/failed]]
    (let [event (payload event-type)]
      (doseq [failure-class failure/failure-classes]
        (is (valid? (assoc event :failure/class failure-class))))
      (is (not (valid? (assoc event :failure/class :failure.class/not-registered)))))))

(deftest ^{:stratum 1} step-labels-and-failure-reasons-retain-their-wire-types
  (doseq [event-type (keys required-fields)]
    (let [event (payload event-type)]
      (doseq [field [:step/id :step/workflow-id] :when (contains? event field)]
        (is (not (valid? (assoc event field (random-uuid)))))
        (is (not (valid? (assoc event field "step")))))
      (doseq [field [:chain/error :edge/failure-reason] :when (contains? event field)]
        (is (not (valid? (assoc event field :error))))))))

(deftest ^{:stratum 1} scope-cross-references-remain-typed-and-optional
  (let [event (payload :chain/started)]
    (is (valid? (assoc event :workflow/id nil)))
    (is (valid? (assoc event :workflow/id (random-uuid))))
    (is (not (valid? (assoc event :workflow/id :workflow/definition))))))

(deftest ^{:stratum 1} unknown-fields-are-open-but-unknown-types-are-not
  (doseq [event-type (keys required-fields)]
    (is (valid? (assoc (payload event-type) :extension/new-field :preserved))))
  (doseq [event-type [:chain/unknown :chain.edge/unknown :workflow/started nil]]
    (is (not (valid? (payload event-type))))))

(comment
  (valid? (payload :chain/started)))
