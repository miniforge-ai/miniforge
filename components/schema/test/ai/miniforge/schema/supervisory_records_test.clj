;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.schema.supervisory-records-test
  (:require [ai.miniforge.schema.interface :as schema]
            [ai.miniforge.schema.supervisory-test-support :as support]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} projection-required-keys-remain-required
  (doseq [[contract record] [[schema/SpecProjection (support/spec-projection)]
                             [schema/InterventionProjection (support/intervention-projection)]]]
    (is (schema/valid? contract record))
    (is (schema/valid? contract (assoc record :extension/unknown :preserved)))
    (doseq [key (keys record)]
      (is (not (schema/valid? contract (dissoc record key)))))))

(deftest ^{:stratum 0} spec-projection-preserves-optional-field-types
  (let [record (support/spec-projection)]
    (doseq [status [:draft :active :completed :archived]]
      (is (schema/valid? schema/SpecProjection (assoc record :spec/status status))))
    (is (schema/valid? schema/SpecProjection (assoc record :spec/intent {} :spec/tags ["tag" :tag])))
    (is (schema/valid? schema/SpecProjection (assoc record :spec/origin :miniforge)))
    (is (not (schema/valid? schema/SpecProjection (assoc record :spec/origin nil))))
    (doseq [[key value] [[:spec/id :label] [:spec/title ""] [:spec/status :unknown]
                        [:spec/created-at "2026-10-04"] [:spec/updated-at nil]
                        [:spec/intent "intent"] [:spec/tags [0]]]]
      (is (not (schema/valid? schema/SpecProjection (assoc record key value)))))))

(deftest ^{:stratum 0} intervention-projection-is-not-an-admission-validator
  (let [record (support/intervention-projection)]
    (is (schema/valid? schema/InterventionProjection (assoc record :intervention/justification nil)))
    (is (schema/valid? schema/InterventionProjection (assoc record :intervention/target-id nil)))
    (doseq [state schema/intervention-states]
      (is (schema/valid? schema/InterventionProjection (assoc record :intervention/state state))))
    (doseq [[key value] [[:intervention/id :label] [:intervention/requested-by ""]
                        [:intervention/state :unknown] [:intervention/requested-at nil]
                        [:intervention/justification :reason] [:intervention/details []]]]
      (is (not (schema/valid? schema/InterventionProjection (assoc record key value)))))))

(comment
  (schema/valid? schema/SpecProjection (support/spec-projection)))
