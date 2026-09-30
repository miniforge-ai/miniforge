;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.pr-model-test
  (:require [ai.miniforge.content-hash.interface :as hash]
            [ai.miniforge.phase-opsv.pr-model :as model]
            [clojure.test :refer [deftest is]])
  (:import [java.util Locale]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} policy-hash (hash/content-hash {:policy :verified}))

(defn- ^{:stratum 0} confirmed-transaction []
  {:effect/id (random-uuid) :effect/grant-id (random-uuid)
   :effect/envelope-id (random-uuid) :effect/state :succeeded
   :effect/class :effect/pr-create :effect/authority :granted
   :effect/observed {:pr/url "https://github.com/example/opsv/pull/17"}})

(deftest ^{:stratum 0} one-effect-identity-per-run-and-repository-test
  (let [run (random-uuid)
        id (model/effect-id run "Example/OPSV")]
    (is (uuid? id))
    (is (= id (model/effect-id run "example/opsv")))
    (is (not= id (model/effect-id (random-uuid) "example/opsv")))
    (is (not= id (model/effect-id run "example/other")))))

(deftest ^{:stratum 0} replay-identity-does-not-depend-on-process-locale-test
  (let [original (Locale/getDefault)
        run (random-uuid)
        expected (model/effect-id run "example/identity")]
    (try
      (Locale/setDefault (Locale/forLanguageTag "tr-TR"))
      (is (= expected (model/effect-id run "EXAMPLE/IDENTITY")))
      (finally (Locale/setDefault original)))))

(deftest ^{:stratum 0} unconfirmed-outcomes-retain-transactions-without-pr-references-test
  (doseq [state [:failed :unknown-outcome]]
    (let [transaction {:effect/id (random-uuid) :effect/state state}
          output (model/outcome {} transaction)]
      (is (= :unavailable (:anomaly/type output)))
      (is (= transaction (get-in output [:anomaly/data :effect/transaction])))
      (is (nil? (:opsv/actuation-record output))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} policy-mismatch-is-refused-before-candidate-preparation-test
  (doseq [[verified prepared] [[policy-hash (hash/content-hash {:policy :other})]
                              [nil nil] ["" ""] [" " " "] [42 42]
                              ["same" "same"]
                              [(apply str (repeat 64 "G")) (apply str (repeat 64 "G"))]]]
    (is (= :conflict (:anomaly/type (model/candidate {} {:opsv/policy-hash verified}
                                                    {:opsv/policy-hash prepared}))))))

(deftest ^{:stratum 1} runtime-workflow-id-aliases-preserve-effect-identity-test
  (let [id (random-uuid)
        verified {:opsv/policy-hash policy-hash}
        target {:pr/repo "example/opsv" :opsv/policy-hash policy-hash}
        candidates (mapv #(model/candidate {% id} verified target)
                          [:execution/id :workflow/id :workflow-id])]
    (is (every? #(= id (:workflow-run/id %)) candidates))
    (is (apply = (map :effect/id candidates)))))

(deftest ^{:stratum 1} malformed-correlation-inputs-cannot-produce-replay-identities-test
  (doseq [[run repository] [[nil "example/opsv"] ["not-a-uuid" "example/opsv"]
                            [(random-uuid) nil] [(random-uuid) ""]
                            [(random-uuid) " "] [(random-uuid) 42]
                            [(random-uuid) "example"] [(random-uuid) "example/opsv/extra"]
                            [(random-uuid) "example/opsv "] [(random-uuid) "/opsv"]]]
    (let [output (model/candidate {:execution/id run} {:opsv/policy-hash policy-hash}
                                  {:pr/repo repository :opsv/policy-hash policy-hash})]
      (is (= :invalid-input (:anomaly/type output)))
      (is (nil? (:effect/id output))))))

(deftest ^{:stratum 1} confirmed-outcome-preserves-governance-and-provider-identity-test
  (doseq [state [:succeeded :reconciled]]
   (let [transaction (assoc (confirmed-transaction) :effect/state state :effect/matched? true)
        output (model/outcome {:requested-actuation-mode :pr-only} transaction)
        record (:opsv/actuation-record output)
        joined (first (:governed-effects record))]
    (is (= :pr-only (:effective-actuation-mode record)))
    (is (= [transaction] (:opsv/effect-transactions output)))
    (is (= [(get-in transaction [:effect/observed :pr/url])] (:pr-refs record)))
    (is (= (mapv transaction [:effect/id :effect/grant-id :effect/envelope-id])
           (mapv joined [:evidence/effect-id :evidence/grant-id :evidence/envelope-id]))))))

(deftest ^{:stratum 1} incomplete-success-or-unmatched-reconciliation-does-not-project-a-pr-test
  (let [transaction (confirmed-transaction)
        missing (mapv #(dissoc transaction %) [:effect/id :effect/grant-id :effect/envelope-id
                                               :effect/observed :effect/class :effect/authority])
        invalid (into missing [(assoc transaction :effect/grant-id "not-a-uuid")
                               (assoc-in transaction [:effect/observed :pr/url] " ")
                               (assoc transaction :effect/class :effect/deploy)
                               (assoc transaction :effect/authority :unenforced)
                               (assoc transaction :effect/state :reconciled :effect/matched? false)])]
    (doseq [value invalid]
      (let [output (model/outcome {} value)]
        (is (= :unavailable (:anomaly/type output)))
        (is (= value (get-in output [:anomaly/data :effect/transaction])))
        (is (nil? (:opsv/actuation-record output)))))))

(comment
  (model/effect-id (random-uuid) "example/opsv"))
