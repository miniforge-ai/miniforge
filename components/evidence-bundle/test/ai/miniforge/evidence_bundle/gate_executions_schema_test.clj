;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.gate-executions-schema-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.walk :as walk]
            [ai.miniforge.evidence-bundle.governance-fixtures :as fixtures]
            [ai.miniforge.evidence-bundle.schema :as schema]
            [ai.miniforge.evidence-bundle.schema.validation :as validation]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} accepted? [value]
  (not-any? #(= :evidence/gate-executions (:key %))
            (:errors (validation/validate-schema schema/evidence-bundle-schema value))))

(defn- ^{:stratum 0} with-severity [record severity]
  (-> record
      (assoc-in [:gate-execution/resolution-trace 0 :rule/severity] severity)
      (assoc-in [:gate-execution/resolution-trace 0 :rule/proposals 0 :rule/severity] severity)
      (assoc-in [:gate-execution/violations 0 :violation/severity] severity)))

(defn- ^{:stratum 0} with-second-rule [record]
  (let [rule (assoc (first (:gate-execution/resolution-trace record)) :rule/id :rule/second)
        violation (assoc (first (:gate-execution/violations record))
                         :violation/id (random-uuid) :violation/rule-id :rule/second)]
    (-> record
        (update :gate-execution/resolved-rules conj :rule/second)
        (update :gate-execution/resolution-trace conj rule)
        (update :gate-execution/violations conj violation))))

(defn- ^{:stratum 0} disabled-rule [record]
  (-> record
      (assoc :gate-execution/outcome :passed :gate-execution/violations [] :gate-execution/waivers [])
      (assoc :gate-execution/resolved-rules [])
      (assoc-in [:gate-execution/resolution-trace 0 :rule/enabled?] false)
      (assoc-in [:gate-execution/resolution-trace 0 :rule/selected?] false)
      (assoc-in [:gate-execution/resolution-trace 0 :rule/proposals 0 :rule/enabled?] false)))

(defn- ^{:stratum 0} with-overlay [record severity enabled?]
  (let [pack (assoc (first (:gate-execution/packs record)) :pack/id :pack/overlay)
        binding (select-keys pack [:pack/id :pack/version])
        proposal {:pack/id :pack/overlay :rule/severity severity :rule/enabled? enabled?}]
    (-> record
        (update :gate-execution/packs conj pack)
        (update-in [:gate-execution/binding :binding/packs] conj binding)
        (update-in [:gate-execution/resolution-trace 0 :rule/proposals] conj proposal))))

(defn- ^{:stratum 0} overlay-only [record]
  (-> record
      (update-in [:gate-execution/binding :binding/packs] subvec 1)
      (update-in [:gate-execution/resolution-trace 0 :rule/proposals] subvec 1)
      (assoc-in [:gate-execution/resolution-trace 0 :rule/severity] :low)
      (assoc-in [:gate-execution/violations 0 :violation/severity] :low)))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} consistently-joined-unqualified-identities-are-not-canonical
  (doseq [replacement [{:pack/example :example} {:rule/example :example}]]
    (let [record (walk/postwalk-replace replacement (fixtures/gate))]
      (is (not (accepted? {:evidence/gate-executions [record]}))))))

(deftest ^{:stratum 1} complete-records-and-empty-history-are-valid
  (let [record (fixtures/gate)]
    (doseq [value [[] [record] [(assoc record :gate-execution/outcome :failed)]
                   [(assoc record :gate-execution/outcome :passed
                                  :gate-execution/violations [] :gate-execution/waivers [])]]]
      (is (accepted? {:evidence/gate-executions value})))
    (is (accepted? {}))))

(deftest ^{:stratum 1} malformed-fields-and-nested-records-are-rejected
  (let [record (fixtures/gate)]
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
                          [[:gate-execution/resolution-trace] nil]
                          [[:gate-execution/resolution-trace] [{}]]
                          [[:gate-execution/packs 0 :pack/id] "example"]
                          [[:gate-execution/resolved-rules] [{}]]
                          [[:gate-execution/resolution-trace 0 :rule/proposals] [nil]]
                          [[:gate-execution/resolution-trace 0 :rule/severity] :unknown]
                          [[:gate-execution/resolved-rules] []]
                          [[:gate-execution/resolution-trace 0 :rule/proposals] []]
                          [[:gate-execution/resolution-trace 0 :rule/proposals 0 :pack/id] :pack/missing]
                          [[:gate-execution/resolution-trace 0 :rule/proposals 0 :rule/severity] :critical]
                          [[:gate-execution/resolution-trace 0 :rule/proposals 0 :rule/enabled?] false]
                          [[:gate-execution/resolution-trace 0 :rule/selected?] false]
                          [[:gate-execution/resolution-trace 0 :pack/id] :pack/missing]
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
  (let [record (fixtures/gate)
        high (with-severity record :high)
        failed (assoc high :gate-execution/outcome :failed :gate-execution/waivers [])
        disabled (disabled-rule record)]
    (is (not (accepted? {:evidence/gate-executions [high]})))
    (is (accepted? {:evidence/gate-executions [failed]}))
    (is (not (accepted? {:evidence/gate-executions [(assoc failed :gate-execution/outcome :passed)]})))
    (is (accepted? {:evidence/gate-executions [disabled]}))
    (doseq [field [:gate-execution/packs :gate-execution/resolved-rules
                   :gate-execution/resolution-trace :gate-execution/violations]]
      (is (not (accepted? {:evidence/gate-executions [(update record field into (get record field))]}))))))

(deftest ^{:stratum 1} contributing-proposals-use-severity-and-disable-precedence
  (let [record (fixtures/gate)
        lower (with-overlay record :low true)
        escalated (with-overlay record :high true)
        disabled (with-overlay (disabled-rule record) :medium false)
        conflicting (assoc-in disabled [:gate-execution/resolution-trace 0 :rule/proposals 0 :rule/enabled?] true)]
    (is (accepted? {:evidence/gate-executions [lower]}))
    (is (not (accepted? {:evidence/gate-executions [escalated]})))
    (is (accepted? {:evidence/gate-executions [conflicting]}))
    (is (not (accepted? {:evidence/gate-executions [(dissoc record :gate-execution/allow-override?)]})))))

(deftest ^{:stratum 1} outcomes-cover-blocking-findings-without-blocking-low-severity
  (let [record (fixtures/gate)
        partial-waiver (with-second-rule record)
        complete (update-in partial-waiver [:gate-execution/waivers 0 :waiver/violations] conj :rule/second)]
    (is (not (accepted? {:evidence/gate-executions [partial-waiver]})))
    (is (accepted? {:evidence/gate-executions [complete]}))
    (doseq [severity [:medium :low :info]]
      (let [passed (assoc (with-severity record severity) :gate-execution/outcome :passed :gate-execution/waivers [])]
        (is (= (not= :medium severity) (accepted? {:evidence/gate-executions [passed]})))
        (is (= (= :medium severity)
               (accepted? {:evidence/gate-executions [(assoc passed :gate-execution/outcome :failed)]})))))))

(deftest ^{:stratum 1} overlay-proposal-can-own-effective-settings-without-owning-check-function
  (let [expanded (with-overlay (fixtures/gate) :low true)
        record (overlay-only expanded)]
    (is (accepted? {:evidence/gate-executions [record]}))))

(deftest ^{:stratum 1} execution-failures-have-no-established-repair-precondition
  (let [record (assoc-in (fixtures/gate) [:gate-execution/violations 0 :failure/class] :failure.class/tool-error)]
    (is (accepted? {:evidence/gate-executions [record]}))
    (is (not (accepted? {:evidence/gate-executions
                         [(assoc-in record [:gate-execution/violations 0 :violation/auto-fixable?] true)]})))
    (let [failed (assoc (with-severity record :low) :gate-execution/outcome :failed :gate-execution/waivers [])]
      (is (accepted? {:evidence/gate-executions [failed]}))
      (is (not (accepted? {:evidence/gate-executions [(assoc failed :gate-execution/outcome :passed)]})))
      (is (accepted? {:evidence/gate-executions [(assoc failed :gate-execution/violations [])]})))))
