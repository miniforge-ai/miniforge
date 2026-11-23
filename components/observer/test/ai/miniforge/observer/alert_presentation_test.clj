;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.observer.alert-presentation-test
  (:require [ai.miniforge.observer.alert-presentation :as presentation]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} rule [type]
  {:alert/id :fixture/rule :alert/type type})

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} localized-defaults-preserve-rendered-values
  (doseq [[type data expected]
          [[:phase-gap {:gap-ms 12} "Phase gap of 12ms exceeds threshold"]
           [:tool-call-timeout {:call-id "c1"} "Tool call c1 timed out"]
           [:tool-error-rate {:error-rate 0.125} "Tool error rate 12.5% exceeds threshold"]]]
    (let [result (presentation/alert (rule type) data)]
      (is (= expected (:alert/message result)))
      (is (= data (:alert/data result)))
      (is (= :warning (:alert/severity result)))
      (is (inst? (:alert/timestamp result))))))

(deftest ^{:stratum 1} caller-template-keeps-original-data-vocabulary
  (let [custom (assoc (rule :phase-gap) :alert/severity :critical
                     :alert/message-template "{{gap-ms}}/{{missing}}")
        result (presentation/alert custom {:gap-ms 12})]
    (is (= "12/" (:alert/message result)))
    (is (= :critical (:alert/severity result)))))

(comment ::alert-presentation-contract)
