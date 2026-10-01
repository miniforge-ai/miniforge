;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.gate-executions-schema-test
  (:require [clojure.test :refer [deftest is]]
            [ai.miniforge.content-hash.interface :as hash]
            [ai.miniforge.evidence-bundle.schema :as schema]
            [ai.miniforge.evidence-bundle.schema.validation :as validation]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} gate []
  (let [evaluation-id (random-uuid)]
    {:gate-execution/gate-id :review :gate-execution/phase :review :gate-execution/outcome :waived
   :gate-execution/evaluation-id evaluation-id :gate-execution/allow-override? true
   :gate-execution/binding {:gate/id :review :binding/packs [{:pack/id :example :pack/version "^2.0.0"}]}
   :gate-execution/packs [{:pack/id "example" :pack/version "2.1.3" :pack/content-hash (hash/content-hash {})}]
   :gate-execution/resolved-rules [{:rule/id :rule/example :pack/id :example :rule/severity :medium
                                   :rule/enabled? true :rule/selected? true
                                   :rule/proposals [{:pack/id :example :rule/severity :medium :rule/enabled? true}]}]
   :gate-execution/violations [{:violation/id (random-uuid) :violation/rule-id :rule/example
                               :violation/pack-id :example :violation/gate-id :review
                               :violation/severity :medium :violation/message "Example finding"
                               :violation/auto-fixable? false :violation/remediation "Review finding"}]
   :gate-execution/waivers [{:waiver/id (random-uuid) :waiver/evaluation-id evaluation-id
                            :waiver/violations [:rule/example] :waiver/actor "operator"
                            :waiver/reason "Accepted for this evaluation" :waiver/timestamp (java.util.Date.)}]}))

(defn- ^{:stratum 0} accepted? [value]
  (not-any? #(= :evidence/gate-executions (:key %))
            (:errors (validation/validate-schema schema/evidence-bundle-schema value))))

(defn- ^{:stratum 0} high-severity [record]
  (-> record
      (assoc-in [:gate-execution/resolved-rules 0 :rule/severity] :high)
      (assoc-in [:gate-execution/resolved-rules 0 :rule/proposals 0 :rule/severity] :high)
      (assoc-in [:gate-execution/violations 0 :violation/severity] :high)))

(defn- ^{:stratum 0} disabled-rule [record]
  (-> record
      (assoc :gate-execution/outcome :passed :gate-execution/violations [] :gate-execution/waivers [])
      (assoc-in [:gate-execution/resolved-rules 0 :rule/enabled?] false)
      (assoc-in [:gate-execution/resolved-rules 0 :rule/selected?] false)
      (assoc-in [:gate-execution/resolved-rules 0 :rule/proposals 0 :rule/enabled?] false)))

(defn- ^{:stratum 0} with-overlay [record severity enabled?]
  (let [pack (assoc (first (:gate-execution/packs record)) :pack/id "overlay")
        proposal {:pack/id :overlay :rule/severity severity :rule/enabled? enabled?}]
    (-> record
        (update :gate-execution/packs conj pack)
        (update-in [:gate-execution/resolved-rules 0 :rule/proposals] conj proposal))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} complete-records-and-empty-history-are-valid
  (let [record (gate)]
    (doseq [value [[] [record] [(assoc record :gate-execution/outcome :failed)]
                   [(assoc record :gate-execution/outcome :passed
                                  :gate-execution/violations [] :gate-execution/waivers [])]]]
      (is (accepted? {:evidence/gate-executions value})))
    (is (accepted? {}))))

(deftest ^{:stratum 1} malformed-fields-and-nested-records-are-rejected
  (let [record (gate)]
    (doseq [value [nil false 42 {} (list record) [nil] [{}]]]
      (is (not (accepted? {:evidence/gate-executions value}))))
    (doseq [field (remove #{:gate-execution/allow-override?} (keys record))]
      (is (not (accepted? {:evidence/gate-executions [(dissoc record field)]}))))
    (doseq [[path value] [[[:gate-execution/outcome] :unknown]
                          [[:gate-execution/binding :gate/id] :other]
                          [[:gate-execution/packs 0 :pack/version] "^2.0"]
                          [[:gate-execution/packs 0 :pack/version] "3.0.0"]
                          [[:gate-execution/binding :binding/packs 0 :pack/version] "2.0.0"]
                          [[:gate-execution/resolved-rules] nil]
                          [[:gate-execution/resolved-rules] [{}]]
                          [[:gate-execution/resolved-rules 0 :rule/proposals] [nil]]
                          [[:gate-execution/resolved-rules 0 :rule/severity] :unknown]
                          [[:gate-execution/resolved-rules] []]
                          [[:gate-execution/resolved-rules 0 :rule/proposals] []]
                          [[:gate-execution/resolved-rules 0 :rule/proposals 0 :pack/id] :missing]
                          [[:gate-execution/resolved-rules 0 :rule/proposals 0 :rule/severity] :critical]
                          [[:gate-execution/resolved-rules 0 :rule/proposals 0 :rule/enabled?] false]
                          [[:gate-execution/resolved-rules 0 :rule/selected?] false]
                          [[:gate-execution/resolved-rules 0 :pack/id] :missing]
                          [[:gate-execution/violations 0 :violation/severity] :high]
                          [[:gate-execution/allow-override?] false]
                          [[:gate-execution/waivers 0 :waiver/evaluation-id] (random-uuid)]
                          [[:gate-execution/packs 0 :pack/content-hash] "invalid"]
                          [[:gate-execution/violations 0 :violation/rule-id] "rule/example"]
                          [[:gate-execution/violations 0 :violation/severity] :critical-ish]
                          [[:gate-execution/waivers 0 :waiver/reason] " "]
                          [[:gate-execution/waivers 0 :waiver/violations] [:rule/missing]]
                          [[:gate-execution/violations] []]
                          [[:gate-execution/outcome] :passed]]]
      (is (not (accepted? {:evidence/gate-executions [(assoc-in record path value)]})) (pr-str path)))))

(deftest ^{:stratum 1} resolved-rule-and-waiver-outcomes-are-consistent
  (let [record (gate)
        high (high-severity record)
        failed (assoc high :gate-execution/outcome :failed :gate-execution/waivers [])
        disabled (disabled-rule record)]
    (is (not (accepted? {:evidence/gate-executions [high]})))
    (is (accepted? {:evidence/gate-executions [failed]}))
    (is (not (accepted? {:evidence/gate-executions [(assoc failed :gate-execution/outcome :passed)]})))
    (is (accepted? {:evidence/gate-executions [disabled]}))
    (doseq [field [:gate-execution/packs :gate-execution/resolved-rules]]
      (is (not (accepted? {:evidence/gate-executions [(update record field into (get record field))]}))))))

(deftest ^{:stratum 1} contributing-proposals-use-severity-and-disable-precedence
  (let [record (gate)
        lower (with-overlay record :low true)
        escalated (with-overlay record :high true)
        disabled (with-overlay (disabled-rule record) :medium false)
        conflicting (assoc-in disabled [:gate-execution/resolved-rules 0 :rule/proposals 0 :rule/enabled?] true)]
    (is (accepted? {:evidence/gate-executions [lower]}))
    (is (not (accepted? {:evidence/gate-executions [escalated]})))
    (is (accepted? {:evidence/gate-executions [conflicting]}))
    (is (not (accepted? {:evidence/gate-executions [(dissoc record :gate-execution/allow-override?)]})))))
