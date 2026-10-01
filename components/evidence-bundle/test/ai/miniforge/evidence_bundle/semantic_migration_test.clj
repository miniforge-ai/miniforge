;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.semantic-migration-test
  (:require [ai.miniforge.evidence-bundle.protocols.impl.semantic-validator :as validator]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} change-lines [n action]
  (map #(str "resource_" % " will be " action) (range n)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} migration-plan [creates destroys]
  (let [lines (concat (change-lines creates "created") (change-lines destroys "destroyed"))
        content (str/join "\n" lines)]
    {:artifact/type :terraform-plan :artifact/content content}))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} producer-rejects-unbalanced-migrations
  (doseq [[creates destroys] [[1 2] [2 1]]]
    (let [result (validator/validate-intent-impl {:intent/type :migrate}
                                               [(migration-plan creates destroys)])]
      (is (false? (:passed? result)))
      (is (= ["semantic-migration-balance"] (mapv :violation/rule-id (:violations result))))
      (is (= :critical (:violation/severity (first (:violations result)))))
      (is (str/includes? (:violation/message (first (:violations result))) "balanced")))))

(comment
  (clojure.test/run-tests 'ai.miniforge.evidence-bundle.semantic-migration-test))
