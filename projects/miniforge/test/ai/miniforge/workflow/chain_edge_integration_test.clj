;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.chain-edge-integration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.redaction.interface :as redaction]
            [ai.miniforge.workflow.chain-binding-report :as report]
            [ai.miniforge.workflow.chain-current-lifecycle-support :as lifecycle]
            [ai.miniforge.workflow.chain-edge-support :as support]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} bindings []
  {:flag [:prev/last-phase-result :flag] :missing [:prev/last-phase-result :missing] :literal "fixture"})

(defn- ^{:stratum 0} refuse-bindings [resolve declarations previous input]
  (if (seq declarations) (throw (ex-info "fixture resolution failure" {}))
      (resolve declarations previous input)))

(deftest ^{:stratum 0} child-inputs-remain-original-while-binding-evidence-is-redacted
  (support/with-fixture
   (fn [opts _ _stream]
     (let [declarations {:alias [:prev/last-phase-result :password]}
           calls (atom [])
           result (support/run-chain opts declarations :completed calls (atom []))
           projection (evidence/create-chain-evidence (support/definition declarations) result)
           records (:step/bindings (second (:chain/step-results result)))]
       (is (= :completed (:chain/status result)))
       (is (= {:alias "fixture-password"} (:input (second @calls))))
       (is (every? #(= (redaction/marker) (:binding/value %)) records))
       (is (not (str/includes? (pr-str projection) "fixture-password")))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} binding-completion-precedes-loading-and-survives-child-failure
  (support/with-fixture
   (fn [opts journal stream]
     (let [calls (atom []) loads (atom [])
           result (support/run-chain opts (bindings) :failed calls loads)
           edge (first (:chain/edges result))
           history (events/get-events stream)
           projection (evidence/create-chain-evidence (support/definition {}) result)]
       (is (= :failed (:chain/status result)))
       (is (= :completed (:edge/status edge)))
       (is (= (mapv :id @calls) [(:edge/from-workflow-id edge) (:edge/to-workflow-id edge)]))
       (is (= {:flag false :literal "fixture"} (:input (second @calls))))
       (is (= :chain.edge/completed (last (second @loads))))
       (is (= 2 (:edge/bindings-count edge) (count (:edge/bindings edge))))
       (is (= [:chain/started :chain/step-started :chain/step-completed :chain/step-started
               :chain.edge/started :chain.edge/completed :chain/step-failed :chain/failed]
              (support/types stream)))
       (is (= (:chain/edges result) (:chain/edges projection)))
       (is (= (:step/bindings (second (:chain/step-results result)))
              (:step/bindings (second (:chain-evidence/step-summaries projection)))))
       (is (every? #(not (contains? (:opts %) :chain/upstream-workflow-id)) @calls))
       (events/close-current-event-stream! stream)
       (let [reopened (events/create-current-event-stream {:journal-directory journal})]
         (try+ (is (= history (events/get-events reopened)))
           (finally (events/close-current-event-stream! reopened))))))))

(deftest ^{:stratum 1} binding-failure-records-edge-and-step-failure-without-loading-child
  (support/with-fixture
   (fn [opts _ stream]
     (let [calls (atom []) loads (atom []) resolve report/resolve-inputs
           result (with-redefs [report/resolve-inputs (partial refuse-bindings resolve)]
                    (support/run-chain opts (bindings) :completed calls loads))]
       (is (= :failed (:chain/status result)))
       (is (= :failed (:edge/status (first (:chain/edges result)))))
       (is (= 1 (count @calls) (count @loads)))
       (is (= [:chain/started :chain/step-started :chain/step-completed :chain/step-started
               :chain.edge/started :chain.edge/failed :chain/step-failed :chain/failed]
              (support/types stream)))))))

(deftest ^{:stratum 1} edge-publication-failures-prevent-dependent-child-effects
  (doseq [type [:chain.edge/started :chain.edge/completed :chain.edge/failed]]
    (support/with-fixture
     (fn [opts _ stream]
       (let [calls (atom []) loads (atom []) publish events/publish! resolve report/resolve-inputs
             resolver (if (= :chain.edge/failed type) (partial refuse-bindings resolve) resolve)
             result (with-redefs [events/publish! (partial lifecycle/reject-type publish type)
                                  report/resolve-inputs resolver]
                      (support/run-chain opts (bindings) :completed calls loads))]
         (is (anomaly/any-anomaly? result))
         (is (= 1 (count @calls) (count @loads)))
         (is (= :chain/failed (last (support/types stream)))))))))

(deftest ^{:stratum 1} optional-missing-bindings-complete-without-inventing-values
  (support/with-fixture
   (fn [opts _ stream]
     (let [result (support/run-chain opts (bindings) :completed (atom []) (atom []))
           edge (first (:chain/edges result))
           missing (first (filter #(= "missing" (:binding/target-input %)) (:edge/bindings edge)))
           another (support/run-chain opts (bindings) :completed (atom []) (atom []))]
       (is (= :completed (:chain/status result)))
       (is (false? (:binding/required? missing)))
       (is (false? (:binding/resolved? missing)))
       (is (not (contains? missing :binding/value)))
       (is (not= (:edge/id edge) (:edge/id (first (:chain/edges another)))))
       (is (= :chain/completed (last (support/types stream))))))))

(deftest ^{:stratum 1} absent-predecessors-and-legacy-mode-do-not-fabricate-edges
  (support/with-fixture
   (fn [opts _ stream]
     (let [definition (update (support/definition (bindings)) :chain/steps #(vector (second %)))
           forged (assoc opts :chain/upstream-workflow-id (random-uuid))
           result (support/run-definition definition forged :completed (atom []) (atom []))
           legacy (support/run-chain (dissoc opts :chain/event-stream :chain/retention-directory)
                                     (bindings) :completed (atom []) (atom []))]
       (is (= :completed (:chain/status result) (:chain/status legacy)))
       (is (= [] (:chain/edges result) (:chain/edges legacy)))
       (is (not-any? #(= "chain.edge" (namespace %)) (support/types stream)))))))

(deftest ^{:stratum 1} receipt-edge-identity-mismatch-prevents-binding-and-child-loading
  (doseq [field [:edge/id :edge/from-workflow-id :edge/to-workflow-id]]
    (support/with-fixture
     (fn [opts _ _stream]
       (let [calls (atom []) loads (atom []) publish events/publish!
             result (with-redefs [events/publish!
                                  (fn [stream event]
                                    (if (= :chain.edge/started (:event/type event))
                                      (assoc event field (random-uuid) :event/sequence-number 0)
                                      (publish stream event)))]
                      (support/run-chain opts (bindings) :completed calls loads))]
         (is (anomaly/any-anomaly? result))
         (is (= 1 (count @calls) (count @loads))))))))

(deftest ^{:stratum 1} later-step-publication-failure-does-not-mask-the-edge-failure
  (support/with-fixture
   (fn [opts _ stream]
     (let [calls (atom []) publish events/publish!
           result (with-redefs [events/publish!
                                (fn [stream event]
                                  (if (contains? #{:chain.edge/completed :chain/step-failed} (:event/type event))
                                    (anomaly/anomaly :unavailable "fixture publication failure"
                                                     {:source (:event/type event)})
                                    (publish stream event)))]
                    (support/run-chain opts (bindings) :completed calls (atom [])))]
       (is (= :chain.edge/completed (get-in result [:anomaly/data :source])))
       (is (= 1 (count @calls)))
       (is (= :chain/failed (last (support/types stream))))))))

(comment
  (bindings))
