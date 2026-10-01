;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.knowledge-inputs-schema-test
  (:require [clojure.test :refer [deftest is]]
            [ai.miniforge.content-hash.interface :as hash]
            [ai.miniforge.evidence-bundle.schema :as schema]
            [ai.miniforge.evidence-bundle.schema.validation :as validation]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} knowledge []
  {:knowledge/id (random-uuid) :knowledge/type :policy-pack
   :knowledge/trust-level :trusted :knowledge/authority :authority/instruction
   :knowledge/source "policy/example" :knowledge/content-hash (hash/content-hash {:rule :example})})

(defn- ^{:stratum 0} accepted? [value]
  (not-any? #(= :evidence/knowledge-inputs (:key %))
            (:errors (validation/validate-schema schema/evidence-bundle-schema value))))

(deftest ^{:stratum 0} malformed-records-and-predicate-errors-fail-closed
  (doseq [value [nil false 42 []]]
    (is (false? (:valid? (validation/validate-schema {} value)))))
  (is (false? (:valid? (validation/validate-schema {:field inc} {:field nil}))))
  (is (thrown? AssertionError
                (validation/validate-schema {:field (fn [_] (throw (AssertionError.)))} {:field 1}))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} knowledge-input-present-values-are-validated
  (let [record (knowledge)]
    (doseq [value [[] [record] [(assoc record :knowledge/signature "signed")]]]
      (is (accepted? {:evidence/knowledge-inputs value})))
    (is (accepted? {}))
    (doseq [value [nil false 42 {} (list record) [nil] [false] [{}]]]
      (is (not (accepted? {:evidence/knowledge-inputs value})) (pr-str value)))
    (doseq [field (keys record)]
      (is (not (accepted? {:evidence/knowledge-inputs [(dissoc record field)]}))))
    (doseq [[field value] [[:knowledge/type :unknown] [:knowledge/trust-level :unknown]
                          [:knowledge/authority :unknown] [:knowledge/content-hash "invalid"]
                          [:knowledge/id "uuid"] [:knowledge/signature nil]]]
      (is (not (accepted? {:evidence/knowledge-inputs [(assoc record field value)]}))))))
