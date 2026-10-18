;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.semantic-rules-test
  (:require [ai.miniforge.evidence-bundle.semantic-rules :as rules]
            [ai.miniforge.evidence-bundle.semantic-analysis :as analysis]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} migrations-require-balanced-resource-replacement
  (doseq [[creates destroys expected] [[1 1 []] [2 2 []] [1 2 [:balance]] [2 1 [:balance]]
                                      [0 1 [:creates :balance]] [1 0 [:destroys :balance]]
                                      [0 0 [:creates :destroys]]]]
    (let [counts (assoc rules/empty-changes :creates creates :destroys destroys)]
      (is (= expected (rules/failed-rules :migrate counts)))))
  (is (= [:updates] (rules/failed-rules :migrate {:creates 1 :updates 1 :destroys 1}))))

(deftest ^{:stratum 0} existing-intent-count-rules-remain-shared
  (doseq [[intent counts expected] [[:import rules/empty-changes []]
                                    [:refactor rules/empty-changes []]
                                    [:create {:creates 1 :updates 2 :destroys 0} []]
                                    [:update {:creates 0 :updates 1 :destroys 0} []]
                                    [:destroy {:creates 0 :updates 0 :destroys 1} []]
                                    [:import {:creates 1 :updates 0 :destroys 0} [:creates]]]]
    (is (= expected (rules/failed-rules intent counts)))))

(deftest ^{:stratum 0} absent-and-nil-artifact-content-remain-empty
  (doseq [type [:terraform-plan :kubernetes-manifest]
          artifact [{:artifact/type type} {:artifact/type type :artifact/content nil}]]
    (is (= rules/empty-changes (analysis/analyze-artifact artifact)))))

(comment
  (clojure.test/run-tests 'ai.miniforge.evidence-bundle.semantic-rules-test))
