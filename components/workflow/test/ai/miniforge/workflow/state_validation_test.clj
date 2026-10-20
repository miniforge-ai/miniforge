;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.state-validation-test
  (:require [ai.miniforge.workflow.state-compiler :as compiler]
            [ai.miniforge.workflow.state-topology :as topology]
            [ai.miniforge.workflow.state-validation :as validation]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} workflow []
  {:workflow/id :validation-order
   :workflow/pipeline [{:phase :plan}]})

(defn- ^{:stratum 0} dangling-topology [workflow]
  (assoc-in (topology/build-raw-states workflow)
            [:states :phase-0-plan :on :phase/succeed]
            {:target :completed
             :guard ::missing-guard
             :actions [::missing-action]}))

(defn- ^{:stratum 0} unreachable-compiler [_]
  (throw (AssertionError. "Invalid references must not reach compilation")))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} references-are-rejected-before-compilation
  (with-redefs [compiler/compile-topology unreachable-compiler]
    (let [result (validation/validate (workflow) dangling-topology)]
      (is (false? (:valid? result)))
      (is (nil? (:machine result)))
      (is (= [:dangling-guard-references :dangling-action-references]
             (mapv :error (:errors result)))))))

(deftest ^{:stratum 1} invalid-pipeline-does-not-build-topology
  (let [invalid (assoc (workflow) :workflow/pipeline [])
        result (validation/validate invalid unreachable-compiler)]
    (is (false? (:valid? result)))
    (is (= [:empty-pipeline] (mapv :error (:errors result))))))

(comment ::validation-bootstrap-order)
