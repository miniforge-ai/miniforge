;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.chain-required-outcome-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.workflow.boundary.chain-step :as child]
            [ai.miniforge.workflow.chain-edge-outcome :as outcome]
            [ai.miniforge.workflow.chain-edge-publication :as publication]
            [ai.miniforge.workflow.chain-edge-record :as record]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} fixture [field]
  (let [declarations {:value {:binding/expression [:prev/last-phase-result field] :binding/required? true}
                      :alias [:prev/last-phase-result :password] :literal "fixture"}
        step {:step/input-bindings declarations}]
    {:report (child/prepare step {:last-phase-result {:flag false :password "fixture-password"}} {})
     :edge (record/started (random-uuid) (random-uuid) (random-uuid)
                           (record/dependencies step) (java.util.Date. 0))}))

(defn- ^{:stratum 0} refused [& _]
  (anomaly/anomaly :unavailable "Fixture publication failure" {}))

(defn- ^{:stratum 0} check-failure [edge bindings result]
  (let [actual (first (:execution/edges result))]
    (is (= :failed (:execution/status result) (:edge/status actual)))
    (is (= bindings (:execution/bindings result)))
    (is (= 3 (count bindings)))
    (is (= 2 (count (:edge/bindings actual))))
    (is (= (record/identity-fields edge) (record/identity-fields actual)))
    (is (= :unavailable (:anomaly/type (:execution/emission-failure result))))
    (is (not (str/includes? (pr-str result) "fixture-password")))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} failed-terminal-publication-keeps-required-resolution-evidence
  (let [{:keys [edge report]} (fixture :missing)
        result (with-redefs [publication/failed! refused] (outcome/failed! {} edge report))]
    (check-failure edge (:execution/bindings report) result)))

(deftest ^{:stratum 1} failed-completion-publication-keeps-all-resolved-bindings
  (let [{:keys [edge report]} (fixture :flag)
        result (with-redefs [publication/completed! refused] (outcome/completed! {} edge report))]
    (check-failure edge (:bindings report) result)))

(comment
  (fixture :missing))
