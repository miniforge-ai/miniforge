;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.gate-records-schema-test
  (:require [clojure.test :refer [deftest is]]
            [ai.miniforge.evidence-bundle.schema.gate-records :as records]
            [ai.miniforge.evidence-bundle.schema.gate-violation :as violation]
            [ai.miniforge.evidence-bundle.schema.governance-values :as values]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} resolved-versions-are-not-ranges
  (doseq [version ["0.0.0" "2.1.3" "1.2.3-rc.1+build.9"]]
    (is (m/validate values/resolved-version version)))
  (doseq [version [nil false "^2.0" "~2.1.3" ">=2.1.3" "2.*" "2.1" "02.1.3" "1.2.3-01" "1.2.3\n"]]
    (is (not (m/validate values/resolved-version version)))))

(deftest ^{:stratum 0} bindings-validate-optional-filters
  (let [binding {:gate/id :review :binding/packs [{:pack/id :example :pack/version "^2.0.0"}]}]
    (is (m/validate records/binding-schema binding))
    (doseq [value [nil false 42 {:filter/phase "review"} {:filter/categories [:security nil]}]]
      (is (not (m/validate records/binding-schema (assoc binding :binding/rule-filter value)))))))

(deftest ^{:stratum 0} remediation-remains-structured
  (is (m/validate violation/remediation {:type :diff :file "example.clj" :patch "patch"}))
  (is (m/validate violation/remediation
                 {:type :replacement :file "example.clj" :line 1 :old-value "old" :new-value "new"}))
  (doseq [value [nil "patch" {} {:type :diff :file "example.clj"}]]
    (is (not (m/validate violation/remediation value)))))
