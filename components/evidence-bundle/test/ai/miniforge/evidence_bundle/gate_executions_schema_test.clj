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
  {:gate-execution/gate-id :review :gate-execution/phase :review :gate-execution/outcome :waived
   :gate-execution/binding {:gate/id :review :binding/packs [{:pack/id :example :pack/version "^2.0.0"}]}
   :gate-execution/packs [{:pack/id "example" :pack/version "2.1.3" :pack/content-hash (hash/content-hash {})}]
   :gate-execution/violations [{:violation/id (random-uuid) :violation/rule-id :rule/example
                               :violation/pack-id :example :violation/gate-id :review
                               :violation/severity :medium :violation/message "Example finding"
                               :violation/auto-fixable? false :violation/remediation "Review finding"}]
   :gate-execution/waivers [{:waiver/id (random-uuid) :waiver/evaluation-id (random-uuid)
                            :waiver/violations [:rule/example] :waiver/actor "operator"
                            :waiver/reason "Accepted for this evaluation" :waiver/timestamp (java.util.Date.)}]})

(defn- ^{:stratum 0} accepted? [value]
  (not-any? #(= :evidence/gate-executions (:key %))
            (:errors (validation/validate-schema schema/evidence-bundle-schema value))))

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
    (doseq [field (keys record)]
      (is (not (accepted? {:evidence/gate-executions [(dissoc record field)]}))))
    (doseq [[path value] [[[:gate-execution/outcome] :unknown]
                          [[:gate-execution/binding :gate/id] :other]
                          [[:gate-execution/packs 0 :pack/version] "^2.0"]
                          [[:gate-execution/packs 0 :pack/version] "3.0.0"]
                          [[:gate-execution/binding :binding/packs 0 :pack/version] "2.0.0"]
                          [[:gate-execution/packs 0 :pack/content-hash] "invalid"]
                          [[:gate-execution/violations 0 :violation/rule-id] "rule/example"]
                          [[:gate-execution/violations 0 :violation/severity] :critical-ish]
                          [[:gate-execution/waivers 0 :waiver/reason] " "]
                          [[:gate-execution/waivers 0 :waiver/violations] [:rule/missing]]
                          [[:gate-execution/violations] []]
                          [[:gate-execution/outcome] :passed]]]
      (is (not (accepted? {:evidence/gate-executions [(assoc-in record path value)]})) (pr-str path)))))
