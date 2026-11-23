;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.execution-grant.opsv-issuance-integration-test
  (:require [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.execution-grant.messages :as msg]
            [ai.miniforge.execution-grant.opsv-commit-fixtures :as commit]
            [ai.miniforge.execution-grant.opsv-issuance-fixtures :as fixtures]
            [ai.miniforge.execution-grant.opsv-scope-fixtures :as scope]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]])
  (:import [java.util Date]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} runtime-issuer-and-current-durable-commit-compose-test
  (doseq [[operation class] [[:trial-apply :effect/deploy] [:restore-baseline :effect/deploy]
                             [:start-load :effect/load-test] [:stop-load :effect/load-test]
                             [:cleanup-load :effect/load-test]]]
    (fs/with-temp-dir [root {:prefix "opsv-runtime-issuance-"}]
      (let [dir (str (fs/canonicalize root))
            request (fixtures/request class operation)
            g (grant/issue-for-effect dir request scope/now)
            transaction (commit/proposed! dir g (scope/scope operation))
            {:keys [result calls stored]} (commit/current! dir transaction scope/now)]
        (is (grant/valid? g))
        (is (nil? (:grant/parent-id g)))
        (is (= :succeeded (:effect/state result)))
        (is (= result stored))
        (is (= 1 (count calls)))))))

(deftest ^{:stratum 0} denied-or-semantic-refusal-never-mints-authority-test
  (doseq [change [:preflight :class :bounds :duration]]
    (fs/with-temp-dir [root {:prefix "opsv-issuer-refusal-"}]
      (let [dir (str (fs/canonicalize root))
            original (fixtures/request :effect/load-test :start-load)
            request (case change
                      :preflight (assoc-in original [:effect/preflight :preflight/result] :deny)
                      :class (assoc original :effect/class :effect/deploy)
                      :bounds (assoc-in original [:opsv/experiment :opsv/load-limits :load/max-requests] 0)
                      :duration (assoc-in original [:opsv/experiment :opsv/duration-ms]
                                          (inc fixtures/maximum-duration-ms)))
            message-key (if (= :preflight change) :issuance/preflight-refused :issuance/policy-refused)
            result (grant/issue-for-effect dir request scope/now)]
        (is (= :unauthorized (:anomaly/type result)))
        (is (= (msg/t message-key) (:anomaly/message result)))
        (is (nil? (:grant/id result)))))))

(deftest ^{:stratum 0} real-breach-history-still-refuses-experiment-issuance-test
  (fs/with-temp-dir [root {:prefix "opsv-breach-eligibility-"}]
    (let [dir (str (fs/canonicalize root))
          request (fixtures/request :effect/load-test :start-load)
          g (grant/issue-for-effect dir request scope/now)
          recorded (grant/record-breach! dir (fixtures/breach g))]
      (is (:breach/id recorded))
      (is (= :unauthorized (:anomaly/type (grant/issue-for-effect dir request scope/now)))))))

(deftest ^{:stratum 0} recovery-issuance-does-not-inherit-revoked-forward-authority-test
  (fs/with-temp-dir [root {:prefix "opsv-independent-recovery-"}]
    (let [dir (str (fs/canonicalize root))
          forward (grant/issue-for-effect dir (fixtures/request :effect/load-test :start-load) scope/now)]
      (is (grant/valid? (grant/register! dir forward)))
      (is (grant/revoked? (grant/revoke-stored! dir (:grant/id forward) :revocation/operator scope/now)))
      (let [recovery (grant/issue-for-effect dir (fixtures/request :effect/load-test :cleanup-load) scope/now)]
        (is (grant/valid? recovery))
        (is (not= (:grant/id forward) (:grant/id recovery)))
        (is (nil? (:grant/parent-id recovery)))
        (is (grant/active? recovery scope/now))))))

(deftest ^{:stratum 0} malformed-request-and-date-clock-retain-boundary-contracts-test
  (fs/with-temp-dir [root {:prefix "opsv-issuer-boundary-"}]
    (let [dir (str (fs/canonicalize root))
          request (fixtures/request :effect/deploy :trial-apply)]
      (doseq [field [:principal :expires-at :constraints :delegable? :grant/id]]
        (is (= :invalid-input (:anomaly/type (grant/issue-for-effect dir (assoc request field :forged) scope/now)))))
      (let [g (grant/issue-for-effect dir request (Date/from scope/now))]
        (is (= scope/now (:grant/issued-at g)))
        (is (= (.plusMillis scope/now (get-in request [:opsv/experiment :opsv/duration-ms]))
               (:grant/expires-at g)))))))

(comment (clojure.test/run-tests 'ai.miniforge.execution-grant.opsv-issuance-integration-test))
