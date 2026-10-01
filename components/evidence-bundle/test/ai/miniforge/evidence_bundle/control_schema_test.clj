;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.control-schema-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.set :as set]
            [ai.miniforge.evidence-bundle.control-fixtures :as fixtures]
            [ai.miniforge.evidence-bundle.schema.domain :as domain]
            [ai.miniforge.evidence-bundle.schema.validation :as validation]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} policy-envelope-is-an-optional-extension
  (doseq [record [(fixtures/policy-check)
                  (assoc (fixtures/policy-check) :policy-check/envelope nil)
                  (assoc (fixtures/policy-check) :policy-check/envelope {})]]
    (is (:valid? (validation/validate-schema domain/policy-check-schema record))))
  (is (false? (:valid? (validation/validate-schema
                        domain/policy-check-schema
                        (assoc (fixtures/policy-check) :policy-check/envelope false))))))

(deftest ^{:stratum 0} portable-control-records-use-normative-keys
  (let [record (fixtures/action)]
    (is (fixtures/valid-action? record))
    (is (fixtures/valid-action? (update record :action/approval
                                       set/rename-keys {:status :approval-status})))
    (is (fixtures/valid-action? (assoc-in record [:action/approval :approval-status] :approved)))
    (doseq [status [:rejected :cancelled nil false]]
      (is (false? (fixtures/valid-action? (assoc-in record [:action/approval :approval-status] status)))))
    (is (false? (fixtures/valid-action? (update record :action/approval dissoc :status))))
    (is (false? (fixtures/valid-action? (assoc-in record [:action/requester :capability] :observe))))
    (is (fixtures/valid-action? (update record :action/requester dissoc :capability)))
    (is (false? (fixtures/valid-action? (assoc record :action/approval {:approval-status :cancelled :approvers []}))))
    (doseq [status [:pending :approved :rejected]]
      (is (fixtures/valid-action? (assoc record :action/approval {:approval-status status :approvers []}))))
    (doseq [path [[:action/id] [:action/type] [:action/timestamp]
                  [:action/requester :principal] [:action/requester :listener-id]
                  [:action/result :status] [:action/approval :approvers]
                  [:action/pre-state] [:action/post-state]]]
      (is (false? (fixtures/valid-action? (assoc-in record path false)))))
    (is (false? (fixtures/valid-action?
                  (set/rename-keys record {:action/id :control-action/id}))))))
