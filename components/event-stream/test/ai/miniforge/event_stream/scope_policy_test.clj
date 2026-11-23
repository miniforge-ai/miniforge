;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.scope-policy-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.commit-test-support :as support]
            [ai.miniforge.event-stream.commit-model :as model]
            [ai.miniforge.event-stream.scope-policy :as policy]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} event [type fields]
  (merge (support/draft) fields {:event/type type}))

(def ^{:private true :stratum 0} chain-types
  [:chain/started :chain/completed :chain/failed
   :chain/step-started :chain/step-completed :chain/step-failed
   :chain.edge/started :chain.edge/completed :chain.edge/failed])

(defn- ^{:stratum 0} chain-event [type run-id workflow-id]
  (merge (support/draft)
         {:event/type type
          :event/version "2.0.0"
          :scope/type :chain
          :chain/run-id run-id
          :chain/definition-id :example/chain
          :workflow/id workflow-id}))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} family-owns-scope-despite-cross-references
  (let [workflow (random-uuid)
        pr (random-uuid)
        fields {:workflow/id workflow :pr/id pr}]
    (doseq [type [:workflow/started :pr/opened :pr/merged :opsv.actuation/emitted]]
      (is (= [:workflow workflow] (policy/scope (event type fields)))))
    (is (= [:pr pr] (policy/scope (event :pr.readiness/changed fields))))))

(deftest ^{:stratum 1} non-workflow-families-require-their-own-key
  (doseq [[type scope key id] [[:pack/installed :pack :pack/id "pack/example"]
                              [:repo-index/canary-failed :repo :repo/id "owner/repo"]
                              [:supervisory/workflow-upserted :supervisory-entity :supervisory/entity-key (random-uuid)]
                              [:reliability/sli-computed :deployment :deployment/id "deployment-test"]
                              [:supervisory/pr-upserted :supervisory-entity :supervisory/entity-key ["owner/repo" 42]]]]
    (let [draft (event type {key id :workflow/id (random-uuid)})]
      (is (= [scope id] (policy/scope draft)))
      (is (anomaly/anomaly? (policy/scope (dissoc draft key)))))))

(deftest ^{:stratum 1} inherited-scope-is-explicit-and-exact
  (let [pr (random-uuid)
        fields {:workflow/id (random-uuid) :pr/id pr}]
    (doseq [type [:listener/attached :annotation/created :control-action/requested]]
      (is (anomaly/anomaly? (policy/scope (event type fields))))
      (is (= [:pr pr] (policy/scope (event type (assoc fields :scope/type :pr)))))
      (is (anomaly/anomaly? (policy/scope (event type (assoc fields :scope/type :unknown))))))))

(deftest ^{:stratum 1} no-synthetic-nil-workflow-bucket
  (doseq [fields [{} {:workflow/id nil} {:pr/id (random-uuid)}]]
    (is (anomaly/anomaly? (policy/scope (event :workflow/started fields))))))

(deftest ^{:stratum 1} unregistered-members-cannot-fall-back-to-workflow
  (doseq [type [:supervisory/unknown :supervisory/intervention-unknown
               :chain/unknown :chain.edge/unknown]
          fields [{} {:workflow/id (random-uuid)}]]
    (is (anomaly/anomaly?
         (policy/scope (event type (assoc fields :supervisory/entity-key (random-uuid))))))))

(deftest ^{:stratum 1} chain-run-owns-every-lifecycle-member
  (let [run-id (random-uuid)
        workflow-id (random-uuid)]
    (doseq [type chain-types]
      (let [draft (chain-event type run-id workflow-id)]
        (is (= [:chain run-id] (policy/scope draft)))
        (is (= [:chain run-id] (policy/scope (dissoc draft :workflow/id))))
        (is (anomaly/anomaly? (policy/scope (dissoc draft :scope/type))))
        (is (anomaly/anomaly? (policy/scope (assoc draft :scope/type :workflow))))
        (is (anomaly/anomaly? (policy/scope (dissoc draft :chain/run-id))))))))

(deftest ^{:stratum 1} chain-scope-can-be-inherited-explicitly
  (let [run-id (random-uuid)
        draft (dissoc (chain-event :listener/attached run-id (random-uuid)) :scope/type)]
    (is (anomaly/anomaly? (policy/scope draft)))
    (is (= [:chain run-id] (policy/scope (assoc draft :scope/type :chain))))))

(deftest ^{:stratum 1} repeated-definitions-have-independent-sequence-counters
  (let [workflow-id (random-uuid)
        first-run (chain-event :chain/started (random-uuid) workflow-id)
        second-run (chain-event :chain/started (random-uuid) workflow-id)
        first-scope (policy/scope first-run)
        second-scope (policy/scope second-run)
        initial (model/empty-state)
        committed (model/candidate initial first-scope first-run)
        advanced (model/accept initial first-scope committed)
        next-run (model/candidate advanced second-scope second-run)]
    (is (not= first-scope second-scope))
    (is (= 0 (:event/sequence-number committed)))
    (is (= 0 (:event/sequence-number next-run)))))

(deftest ^{:stratum 1} supervisory-facts-and-spec-snapshots-own-entity-scope
  (let [entity-id (random-uuid)
        fields {:supervisory/entity-key entity-id
                :workflow/id (random-uuid)
                :scope/type :supervisory-entity}]
    (doseq [type [:supervisory/spec-upserted :supervisory/intervention-requested
                 :supervisory/intervention-state-changed]]
      (let [draft (event type fields)]
        (is (= [:supervisory-entity entity-id] (policy/scope draft)))
        (is (= [:supervisory-entity entity-id] (policy/scope (dissoc draft :workflow/id))))
        (is (anomaly/anomaly? (policy/scope (dissoc draft :supervisory/entity-key))))))))

(deftest ^{:stratum 1} intervention-profile-discriminator-is-required
  (doseq [type [:supervisory/intervention-requested :supervisory/intervention-state-changed]]
    (let [draft (event type {:supervisory/entity-key (random-uuid)})]
      (is (anomaly/anomaly? (policy/scope draft)))
      (is (anomaly/anomaly? (policy/scope (assoc draft :scope/type :workflow)))))))

(deftest ^{:stratum 1} fixed-families-reject-conflicting-discriminators
  (doseq [[type scope key id] [[:workflow/started :workflow :workflow/id (random-uuid)]
                              [:pack/installed :pack :pack/id "example-pack"]
                              [:supervisory/spec-upserted :supervisory-entity :supervisory/entity-key (random-uuid)]]]
    (let [draft (event type {key id})
          resolved [scope id]]
      (is (= resolved (policy/scope draft)))
      (is (= resolved (policy/scope (assoc draft :scope/type scope))))
      (is (= resolved (policy/scope (assoc draft :scope/type nil))))
      (is (anomaly/anomaly? (policy/scope (assoc draft :scope/type :pr))))
      (is (anomaly/anomaly? (policy/scope (assoc draft :scope/type :unregistered)))))))

(comment
  ::authoritative-scope)
