;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.observer.alert-conditions-test
  (:require [ai.miniforge.observer.alert-conditions :as conditions]
            [ai.miniforge.observer.alert-spec :as spec]
            [clojure.test :refer [deftest is]]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} strict-thresholds-and-absent-observations
  (is (nil? (conditions/phase-gap 10 10)))
  (is (nil? (conditions/phase-gap 10 nil)))
  (is (= {:gap-ms 11 :threshold-ms 10} (conditions/phase-gap 10 11)))
  (is (nil? (conditions/error-rate 0.5 [true false])))
  (is (nil? (conditions/error-rate 0.5 [])))
  (is (= {:error-rate 1.0 :failure-count 1 :total-count 1 :threshold-pct 0.5}
         (conditions/error-rate 0.5 [false]))))

(deftest ^{:stratum 0} shared-rule-schema-retains-all-threshold-variants
  (doseq [[type threshold] [[:phase-gap {:threshold-ms 1}]
                           [:tool-call-timeout {:threshold-ms 1}]
                           [:tool-error-rate {:threshold-pct 0.5}]]]
    (let [rule {:alert/id :fixture/rule :alert/type type :alert/threshold threshold}]
      (is (m/validate spec/AlertRule rule))
      (is (not (m/validate spec/AlertRule (assoc rule :alert/severity :invalid)))))))

(comment ::alert-threshold-contracts)
