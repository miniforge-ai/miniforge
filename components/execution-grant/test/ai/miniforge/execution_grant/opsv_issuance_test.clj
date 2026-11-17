;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.execution-grant.opsv-issuance-test
  (:require [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.execution-grant.issuance.policy :as policy]
            [ai.miniforge.execution-grant.issuance.schema :as input]
            [ai.miniforge.execution-grant.issuance.selection :as selection]
            [ai.miniforge.execution-grant.opsv-issuance-fixtures :as fixtures]
            [ai.miniforge.execution-grant.opsv-scope-fixtures :as scope]
            [clojure.test :refer [deftest is]]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} valid-arguments? (m/validator input/Arguments))

(deftest ^{:stratum 0} explicit-profile-selection-precedes-generic-effect-class-test
  (let [deployment (fixtures/request :effect/deploy :trial-apply)
        load (fixtures/request :effect/load-test :start-load)]
    (is (= :opsv/experiment (selection/profile-key deployment)))
    (is (= :opsv/experiment (selection/profile-key load)))
    (is (= :opsv/experiment (selection/profile-key (dissoc load :opsv/experiment))))
    (is (= :effect/deploy (selection/profile-key (dissoc deployment :opsv/experiment))))))

(deftest ^{:stratum 0} maximum-duration-is-an-explicit-policy-cap-test
  (let [request (assoc-in (fixtures/request :effect/deploy :trial-apply)
                          [:opsv/experiment :opsv/duration-ms] fixtures/maximum-duration-ms)
        selected (:opsv/experiment grant/issuance-policies)]
    (is (= fixtures/maximum-duration-ms (.toMillis (java.time.Duration/ofSeconds (:policy/ttl-seconds selected)))))
    (is (policy/admissible-request? request selected))
    (is (= (.plusMillis scope/now fixtures/maximum-duration-ms)
           (:expires-at (policy/grant-options request scope/now))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} canonical-staging-operations-have-explicit-issuer-profiles-test
  (doseq [[operation class] [[:trial-apply :effect/deploy] [:restore-baseline :effect/deploy]
                             [:start-load :effect/load-test] [:stop-load :effect/load-test]
                             [:cleanup-load :effect/load-test]]]
    (let [request (fixtures/request class operation)
          selected (:opsv/experiment grant/issuance-policies)
          options (policy/grant-options request scope/now)]
      (is (valid-arguments? [request scope/now]))
      (is (policy/admissible-request? request selected))
      (is (= (scope/scope operation) (:scope options)))
      (is (= class (:effect-class options)))
      (is (= (str "runtime:workflow-run/" scope/workflow-id) (:principal options)))
      (is (= {:constraint/max-count 1} (:constraints options)))
      (is (false? (:delegable? options)))
      (is (= (.plusMillis scope/now (get-in request [:opsv/experiment :opsv/duration-ms]))
             (:expires-at options))))))

(deftest ^{:stratum 1} every-experiment-binding-and-runtime-basis-remains-required-test
  (let [request (fixtures/request :effect/deploy :trial-apply)]
    (doseq [field (keys request)]
      (is (false? (valid-arguments? [(dissoc request field) scope/now]))))
    (doseq [field (keys (:opsv/experiment request))]
      (is (false? (valid-arguments? [(update request :opsv/experiment dissoc field) scope/now]))))
    (doseq [field [:principal :expires-at :constraints :delegable? :grant/id :default-context :kustomize-dir]]
      (is (false? (valid-arguments? [(assoc request field :caller-selected) scope/now]))))))

(deftest ^{:stratum 1} profiles-never-fall-back-to-generic-effect-inputs-test
  (let [request (fixtures/request :effect/deploy :trial-apply)]
    (doseq [other [(assoc request :effect/class :effect/merge)
                   (assoc request :workflow-run/status :completed)
                   (assoc-in request [:effect/preflight :preflight/type] :preflight/deploy-policy-and-dry-run)
                   (dissoc (assoc request :effect/class :effect/load-test) :opsv/experiment)]]
      (is (false? (valid-arguments? [other scope/now]))))))

(deftest ^{:stratum 1} well-typed-but-unsafe-request-policy-is-refused-test
  (doseq [[class operation path value]
          [[:effect/load-test :start-load [:opsv/traffic-destinations] []]
           [:effect/load-test :start-load [:opsv/load-limits :load/max-requests] 0]
           [:effect/load-test :start-load [:opsv/load-limits :load/max-concurrency] 0]
           [:effect/load-test :start-load [:opsv/load-limits :load/max-rate-per-second] 0]
           [:effect/load-test :cleanup-load [:opsv/resources 0 :uid] nil]
           [:effect/deploy :trial-apply [:opsv/duration-ms] (inc fixtures/maximum-duration-ms)]]]
    (let [request (assoc-in (fixtures/request class operation) (into [:opsv/experiment] path) value)]
      (is (valid-arguments? [request scope/now]))
      (is (false? (policy/admissible-request? request (:opsv/experiment grant/issuance-policies)))))))

(deftest ^{:stratum 1} a-deployment-request-cannot-issue-load-authority-test
  (let [request (fixtures/request :effect/deploy :start-load)]
    (is (valid-arguments? [request scope/now]))
    (is (false? (policy/admissible-request? request (:opsv/experiment grant/issuance-policies))))))

(deftest ^{:stratum 1} stopped-trials-admit-only-named-compensation-test
  (doseq [[operation class] fixtures/recovery-cases
          status fixtures/stopped-statuses]
    (let [request (assoc (fixtures/request class operation) :workflow-run/status status)]
      (is (valid-arguments? [request scope/now]))))
  (doseq [[operation class] [[:trial-apply :effect/deploy] [:start-load :effect/load-test]]
          status fixtures/stopped-statuses]
    (let [request (assoc (fixtures/request class operation) :workflow-run/status status)]
      (is (false? (valid-arguments? [request scope/now]))))))

(deftest ^{:stratum 1} recovery-does-not-admit-unstarted-completed-or-unknown-runs-test
  (doseq [[operation class] fixtures/recovery-cases
          status [:queued :completed :unknown nil]]
    (let [request (assoc (fixtures/request class operation) :workflow-run/status status)]
      (is (false? (valid-arguments? [request scope/now]))))))

(comment (clojure.test/run-tests 'ai.miniforge.execution-grant.opsv-issuance-test))
