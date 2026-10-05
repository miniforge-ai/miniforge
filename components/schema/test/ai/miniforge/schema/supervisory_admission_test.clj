;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.schema.supervisory-admission-test
  (:require [ai.miniforge.schema.interface :as schema]
            [ai.miniforge.schema.supervisory-test-support :as support]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} current-write-fields-are-required-without-changing-projections
  (doseq [[projection contract record field]
          [[schema/SpecProjection schema/SpecRecord (support/spec-record) :spec/origin]
           [schema/InterventionProjection schema/InterventionRecord
            (support/intervention-record) :intervention/justification]]]
    (is (schema/valid? contract record))
    (is (schema/valid? projection (dissoc record field)))
    (is (not (schema/valid? contract (dissoc record field))))
    (is (not (schema/valid? contract (assoc record field nil))))))

(deftest ^{:stratum 0} spec-titles-require-content-not-just-length
  (let [record (support/spec-record)]
    (doseq [title [nil "" " " "\t\n" "\u2003" "\u00a0" "\u3000" "\u202f" "\u0085" :title]]
      (is (not (schema/valid? schema/SpecRecord (assoc record :spec/title title)))))
    (doseq [title ["A" "\nA\n" "仕様" "\u2003仕様\u00a0"]]
      (is (schema/valid? schema/SpecRecord (assoc record :spec/title title))))
    (is (schema/valid? schema/SpecProjection (assoc record :spec/title " ")))
    (is (not (schema/valid? schema/SpecRecord (assoc record :spec/origin "miniforge"))))))

(deftest ^{:stratum 0} admission-composes-canonical-record-requirements
  (doseq [[contract record] [[schema/SpecRecord (support/spec-record)]
                             [schema/InterventionRecord (support/intervention-record)]]]
    (doseq [field (keys record)]
      (is (not (schema/valid? contract (dissoc record field)))))
    (is (schema/valid? contract (assoc record :extension/unknown :preserved)))))

(deftest ^{:stratum 0} intervention-record-shape-does-not-grant-authority
  (let [record (support/intervention-record)]
    (doseq [state [:proposed :pending-human :approved :rejected :dispatched :applied :verified :failed]]
      (is (schema/valid? schema/InterventionRecord (assoc record :intervention/state state))))
    (is (not (schema/valid? schema/InterventionRecord (assoc record :intervention/state :unknown))))
    (is (not (schema/valid? schema/InterventionRecord (assoc record :intervention/justification :reason))))
    (is (schema/valid? schema/InterventionRecord (assoc record :intervention/justification "")))
    (is (schema/valid? schema/InterventionRecord (assoc record :intervention/target-id nil)))))

(comment
  (schema/valid? schema/SpecRecord (support/spec-record)))
