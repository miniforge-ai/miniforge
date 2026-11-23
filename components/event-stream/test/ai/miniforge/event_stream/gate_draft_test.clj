;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.gate-draft-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.decision-envelope.interface :as decision]
            [ai.miniforge.event-stream.commit-test-support :as support]
            [ai.miniforge.event-stream.event-type-registry :as registry]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.redaction.interface :as redaction]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} envelope [obligations]
  (decision/envelope [] obligations
                     {:pins/pack-revision nil :pins/rule-ids [:fixture/rule] :pins/event-watermark nil}))

(defn- ^{:stratum 0} violation []
  {:message "fixture violation" :artifact-path nil :match-count 1})

(defn- ^{:stratum 0} draft [event-type payload]
  (merge (support/draft) payload
         {:event/type event-type :scope/type :workflow :workflow/id (random-uuid) :gate/phase :implement}))

(defn- ^{:stratum 0} refused? [event]
  (anomaly/anomaly? (events/prepare-current-publication event)))

(deftest ^{:stratum 0} gate-types-are-registered-as-workflow-audit-evidence
  (let [entries (into {} (map (juxt :event-type identity)) registry/event-type-registry)]
    (doseq [event-type [:gate/decision :gate/rule-applied]]
      (is (= :workflow (:scope (get entries event-type))))
      (is (= :audit (:retention-class (get entries event-type)))))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} decision-draft [obligations]
  (let [value (envelope obligations)]
    (draft :gate/decision {:envelope/id (:envelope/id value) :gate/decision-envelope value})))

(defn- ^{:stratum 1} rule-draft [status]
  (draft :gate/rule-applied {:rule/id :fixture/rule :rule/status status}))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} runtime-decisions-preserve-all-three-outcomes
  (doseq [[obligations expected] [[[] :allow]
                                 [[{:obligation/type :obligation/approval-required}] :deny]
                                 [[{:obligation/type :obligation/audit-recorded}] :allow-with-obligations]]]
    (let [event (decision-draft obligations)]
      (is (= expected (get-in event [:gate/decision-envelope :envelope/decision])))
      (is (= [[:workflow (:workflow/id event)] event] (events/prepare-current-publication event)))
      (is (refused? (assoc event :envelope/id (random-uuid))))
      (is (refused? (assoc-in event [:gate/decision-envelope :envelope/produced-by] :agent)))
      (doseq [label (remove #{expected} decision/decisions)]
        (is (refused? (assoc-in event [:gate/decision-envelope :envelope/decision] label)))))))

(deftest ^{:stratum 2} malformed-envelope-and-rule-payloads-are-rejected
  (let [event (decision-draft [])]
    (doseq [field (keys (:gate/decision-envelope event))]
      (is (refused? (update event :gate/decision-envelope dissoc field))))
    (is (refused? (assoc event :gate/decision-envelope {})))
    (is (refused? (assoc-in event [:gate/decision-envelope :envelope/reasons]
                           [{:reason/code :unknown :reason/detail "fixture reason"}]))))
  (doseq [changed [{:rule/id "not-a-keyword"} {:rule/status :unknown}
                   {:rule/severity :major} {:rule/enforcement :unknown}
                   {:envelope/id "not-a-uuid"} {:rule/violation {:match-count -1}}]]
    (is (refused? (merge (rule-draft :failed) changed)))))

(deftest ^{:stratum 2} each-profile-requires-identity-and-payload-without-caller-positions
  (doseq [[event required] [[(decision-draft []) [:envelope/id :gate/decision-envelope]]
                           [(rule-draft :passed) [:rule/id :rule/status]]]]
    (is (not (refused? event)))
    (doseq [field (concat [:workflow/id :gate/phase :scope/type :event/version] required)]
      (is (refused? (dissoc event field))))
    (doseq [changed [{:event/sequence-number 0} {:event/version "2.0.0"}
                     {:scope/type :chain} {:workflow/id nil} {:timestamp nil}
                     {:workflow/phase :review} {:gate/phase nil}]]
      (is (refused? (merge event changed))))
    (is (not (refused? (assoc event :workflow/phase nil))))
    (is (not (refused? (assoc event :workflow/phase :implement :future/field true))))))

(deftest ^{:stratum 2} rule-statuses-and-optional-fields-retain-their-meaning
  (doseq [status [:passed :failed :skipped-by-phase :not-applicable :guidance]]
    (let [event (rule-draft status)]
      (is (not (refused? event)))
      (doseq [field [:envelope/id :rule/severity :rule/enforcement :rule/violation]]
        (is (not (refused? (assoc event field nil)))))
      (is (= (not= :failed status) (refused? (assoc event :rule/violation (violation)))))))
  (doseq [severity [:critical :high :medium :low :info]
          enforcement [:hard-halt :require-approval :warn :audit]]
    (is (not (refused? (assoc (rule-draft :passed)
                             :rule/severity severity :rule/enforcement enforcement :envelope/id (random-uuid)))))))

(deftest ^{:stratum 2} sensitive-decision-content-fails-without-leaking-or-rewriting-provenance
  (let [event (decision-draft [{:obligation/type :obligation/audit-recorded}])]
    (doseq [path [[:gate/decision-envelope :envelope/pins :pins/pack-revision]
                  [:gate/decision-envelope :envelope/obligations 0 :obligation/detail]]]
      (let [result (events/prepare-current-publication (assoc-in event path "AKIAIOSFODNN7EXAMPLE"))]
        (is (anomaly/anomaly? result))
        (is (redaction/clean? result))))))

(deftest ^{:stratum 2} violation-summaries-allow-redaction-but-never-raw-matches
  (let [summary (assoc (violation) :message "AKIAIOSFODNN7EXAMPLE")
        event (assoc (rule-draft :failed) :rule/violation summary)
        result (events/prepare-current-publication event)]
    (is (vector? result))
    (is (= (redaction/marker) (get-in (second result) [:rule/violation :message])))
    (is (refused? (assoc-in event [:rule/violation :matches] ["raw source"])))
    (is (refused? (assoc event :rule/violation {})))))

(comment
  (clojure.test/run-tests 'ai.miniforge.event-stream.gate-draft-test))
