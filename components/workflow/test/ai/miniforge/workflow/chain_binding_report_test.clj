;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.chain-binding-report-test
  (:require [ai.miniforge.workflow.chain-binding-report :as report]
            [ai.miniforge.workflow.chain-bindings :as bindings]
            [ai.miniforge.workflow.chain-test-support :as support]
            [ai.miniforge.workflow.interface :as workflow]
            [ai.miniforge.workflow.loader :as loader]
            [ai.miniforge.workflow.runner :as runner]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} declarations []
  {:literal "fixture" :flag :flag :missing :missing :task :chain/input.task
   :upstream/value [:prev/last-phase-result :value]})

(defn- ^{:stratum 0} fail! [& _] (throw (ex-info "fixture failure" {})))

(defn- ^{:stratum 0} completed [id]
  (cond-> {:execution/status :completed} id (assoc :execution/id id)))

(defn- ^{:stratum 0} records-by-target [records]
  (into {} (map (juxt :binding/target-input identity)) records))

(defn- ^{:stratum 0} definition []
  (assoc-in (support/definition :binding-evidence "1.0.0")
            [:chain/steps 0 :step/input-bindings] {:flag :flag :missing :missing}))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} resolution-records-preserve-the-deployed-vocabulary
  (let [calls (atom []) resolve bindings/resolve-binding
        previous {:last-phase-result {:value 42}}
        input {:flag false :task "task" :missing nil}
        result (with-redefs [bindings/resolve-binding
                             (fn [expression previous input]
                               (swap! calls conj expression) (resolve expression previous input))]
                 (report/resolve-inputs (declarations) previous input))
        records (records-by-target (:bindings result))]
    (is (= {:literal "fixture" :flag false :task "task" :upstream/value 42} (:inputs result)))
    (is (= (count (declarations)) (count @calls) (count records)))
    (is (false? (:binding/value (get records "flag"))))
    (is (:binding/resolved? (get records "flag")))
    (is (false? (:binding/resolved? (get records "missing"))))
    (is (not (contains? (get records "missing") :binding/value)))
    (is (= [:prev/last-phase-result :value] (:binding/expression (get records "upstream/value"))))
    (is (every? false? (map :binding/required? (vals records)))))
  (is (= {:inputs {} :bindings []} (report/resolve-inputs nil nil nil))))

(deftest ^{:stratum 1} public-chain-retains-the-inputs-actually-sent-to-the-child
  (let [seen (atom [])
        result (with-redefs [loader/load-workflow (constantly {:workflow {}})
                             runner/run-pipeline (fn [_ input _] (swap! seen conj input) (completed nil))]
                 (workflow/run-chain (definition) {:flag false} {}))
        step (first (:chain/step-results result))
        records (records-by-target (:step/bindings step))]
    (is (= :completed (:chain/status result)))
    (is (= [{:flag false}] @seen))
    (is (uuid? (:step/execution-id step)))
    (is (= false (:binding/value (get records "flag")))))
  (let [result (with-redefs [loader/load-workflow (constantly {:workflow {}})
                             runner/run-pipeline (constantly (completed nil))]
                 (workflow/run-chain (support/definition :no-bindings "1.0.0") {} {}))]
    (is (= [] (:step/bindings (first (:chain/step-results result)))))))

(deftest ^{:stratum 1} child-failures-cannot-erase-successfully-resolved-bindings
  (doseq [[load run] [[fail! (constantly (completed nil))]
                     [(constantly {:anomaly/category :anomalies/not-found}) (constantly (completed nil))]
                     [(constantly {:workflow {}}) fail!]
                     [(constantly {:workflow {}}) (constantly {:execution/status :failed})]
                     [(constantly {:workflow {}}) (constantly (completed (random-uuid)))]]]
    (let [result (with-redefs [loader/load-workflow load runner/run-pipeline run]
                   (workflow/run-chain (definition) {:flag false} {}))
          step (first (:chain/step-results result))
          records (records-by-target (:step/bindings step))]
      (is (= :failed (:chain/status result)))
      (is (= :failed (:step/status step)))
      (is (uuid? (:step/execution-id step)))
      (is (= false (:binding/value (get records "flag"))))
      (is (false? (:binding/resolved? (get records "missing")))))))

(comment
  (definition))
