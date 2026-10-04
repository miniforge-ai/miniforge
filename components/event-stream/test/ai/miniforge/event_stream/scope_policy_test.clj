;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.scope-policy-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.commit-test-support :as support]
            [ai.miniforge.event-stream.scope-policy :as policy]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} event [type fields]
  (merge (support/draft) fields {:event/type type}))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} family-owns-scope-despite-cross-references
  (let [workflow (random-uuid)
        pr (random-uuid)
        fields {:workflow/id workflow :pr/id pr :scope/type :pr}]
    (doseq [type [:workflow/started :pr/opened :pr/merged :opsv.actuation/emitted]]
      (is (= [:workflow workflow] (policy/scope (event type fields)))))
    (is (= [:pr pr] (policy/scope (event :pr.readiness/changed fields))))))

(deftest ^{:stratum 1} non-workflow-families-require-their-own-key
  (doseq [[type scope key id] [[:pack/installed :pack :pack/id "pack/example"]
                              [:repo-index/canary-failed :repo :repo/id "owner/repo"]
                              [:supervisory/spec-upserted :supervisory-entity :supervisory/entity-key (random-uuid)]
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

(comment
  ::authoritative-scope)
