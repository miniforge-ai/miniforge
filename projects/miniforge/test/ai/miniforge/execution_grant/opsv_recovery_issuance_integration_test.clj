;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.execution-grant.opsv-recovery-issuance-integration-test
  (:require [ai.miniforge.effect-transaction.interface :as fx]
            [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.execution-grant.opsv-commit-fixtures :as commit]
            [ai.miniforge.execution-grant.opsv-issuance-fixtures :as fixtures]
            [ai.miniforge.execution-grant.opsv-scope-fixtures :as scope]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} effect! [calls record]
  (swap! calls conj record)
  (commit/success))

(defn- ^{:stratum 0} usage [scope]
  {:effect/scope scope :usage/count 1})

(deftest ^{:stratum 0} interrupted-trials-can-obtain-exact-independent-compensation-test
  (doseq [[operation class] fixtures/recovery-cases
          status fixtures/stopped-statuses]
    (fs/with-temp-dir [root {:prefix "opsv-stopped-recovery-"}]
      (let [request (assoc (fixtures/request class operation) :workflow-run/status status)
            issued (grant/issue-for-effect (str (fs/canonicalize root)) request scope/now)]
        (is (grant/valid? issued))))))

(deftest ^{:stratum 0} stopped-workflows-cannot-obtain-new-forward-authority-test
  (doseq [[operation class] [[:trial-apply :effect/deploy] [:start-load :effect/load-test]]
          status fixtures/stopped-statuses]
    (fs/with-temp-dir [root {:prefix "opsv-stopped-forward-"}]
      (let [request (assoc (fixtures/request class operation) :workflow-run/status status)
            issued (grant/issue-for-effect (str (fs/canonicalize root)) request scope/now)]
        (is (= :invalid-input (:anomaly/type issued)))
        (is (nil? (:grant/id issued)))))))

(deftest ^{:stratum 0} stopped-recovery-still-requires-admission-and-resource-uid-test
  (fs/with-temp-dir [root {:prefix "opsv-recovery-refusal-"}]
    (let [dir (str (fs/canonicalize root))
          request (assoc (fixtures/request :effect/load-test :cleanup-load) :workflow-run/status :cancelled)
          denied (assoc-in request [:effect/preflight :preflight/result] :deny)
          unproven (assoc-in request [:opsv/experiment :opsv/resources 0 :uid] nil)]
      (is (= :unauthorized (:anomaly/type (grant/issue-for-effect dir denied scope/now))))
      (is (= :unauthorized (:anomaly/type (grant/issue-for-effect dir unproven scope/now))))
      (is (nil? (:grant/id (grant/issue-for-effect dir denied scope/now)))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} stopped-recovery-commits-once-under-new-pinned-authority-test
  (doseq [[operation class] fixtures/recovery-cases
          status fixtures/stopped-statuses]
    (fs/with-temp-dir [root {:prefix "opsv-recovery-commit-"}]
      (let [dir (str (fs/canonicalize root))
            forward (grant/issue-for-effect dir (fixtures/request :effect/load-test :start-load) scope/now)
            _registered (grant/register! dir forward)
            revoked (grant/revoke-stored! dir (:grant/id forward) :revocation/operator scope/now)
            request (assoc (fixtures/request class operation)
                           :workflow-run/status status :effect/id (random-uuid))
            recovery (grant/issue-for-effect dir request scope/now)
            proposal (commit/proposed! dir recovery (:grant/scope recovery))
            calls (atom [])
            result (fx/commit-current! dir (:effect/id proposal) (:grant/id recovery)
                                       (partial grant/current dir) (constantly scope/now)
                                       (partial effect! calls))]
        (is (grant/revoked? revoked))
        (is (not= (:grant/id forward) (:grant/id recovery)))
        (is (nil? (:grant/parent-id recovery)))
        (is (false? (:grant/delegable? recovery)))
        (is (= :succeeded (:effect/state result)))
        (is (= result (fx/read-record dir (:effect/id proposal))))
        (is (= 1 (count @calls)))
        (is (= :committing (:effect/state (first @calls))))
        (is (= :conflict
               (:anomaly/type (fx/commit-current! dir (:effect/id proposal) (:grant/id recovery)
                                                 (partial grant/current dir) (constantly scope/now)
                                                 (partial effect! calls)))))
        (is (= result (fx/read-record dir (:effect/id proposal))))
        (is (= 1 (count @calls)))))))

(deftest ^{:stratum 1} stopped-recovery-does-not-weaken-scope-expiry-or-count-test
  (fs/with-temp-dir [root {:prefix "opsv-recovery-limits-"}]
    (let [dir (str (fs/canonicalize root))
          request (assoc (fixtures/request :effect/deploy :restore-baseline) :workflow-run/status :paused)
          g (grant/issue-for-effect dir request scope/now)
          original (:grant/scope g)
          expired (.plusNanos (:grant/expires-at g) 1)
          changed (assoc-in original [:opsv/experiment :opsv/operation] :trial-apply)
          renamed (assoc-in original [:opsv/experiment :opsv/resources 0 :uid] "reused-name-foreign-uid")]
      (is (= :inactive (:grant/outcome (grant/authorize g (usage original) expired))))
      (is (= :scope-mismatch (:grant/outcome (grant/authorize g (usage changed) scope/now))))
      (is (= :scope-mismatch (:grant/outcome (grant/authorize g (usage renamed) scope/now))))
      (is (= :exceeded (:grant/outcome (grant/authorize g (assoc (usage original) :usage/count 2) scope/now)))))))

(comment (clojure.test/run-tests 'ai.miniforge.execution-grant.opsv-recovery-issuance-integration-test))
