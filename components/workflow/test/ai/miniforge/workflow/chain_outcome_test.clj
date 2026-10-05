;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.chain-outcome-test
  (:require [ai.miniforge.workflow.chain-bindings :as bindings]
            [ai.miniforge.workflow.chain-outcome :as outcome]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} bindings-preserve-false-and-omit-only-nil
  (is (= {:flag false} (bindings/resolve-bindings {:flag :flag :absent :missing} nil {:flag false})))
  (is (= {} (bindings/resolve-bindings nil nil nil)))
  (is (= 3 (bindings/resolve-binding [:custom :value] {:value 3} nil))))

(deftest ^{:stratum 0} child-outcomes-are-normalized-without-losing-results
  (let [completed {:execution/status :completed :execution/output {:value 3}}
        failed (assoc completed :execution/status :failed :execution/error "failure")]
    (is (identical? completed (outcome/normalize completed)))
    (is (= failed (outcome/normalize failed)))
    (is (= :failed (:execution/status (outcome/normalize nil))))))

(deftest ^{:stratum 0} completion-requires-an-unambiguous-execution-status
  (doseq [result [{:status :completed}
                  {:execution/status :completed :anomaly/type :fault}
                  {:execution/status :completed :anomaly/category nil}]]
    (is (not (outcome/completed? result)))
    (is (= :failed (:execution/status (outcome/normalize result))))))

(comment
  (outcome/normalize nil))
