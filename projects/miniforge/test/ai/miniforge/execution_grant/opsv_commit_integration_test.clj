;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.execution-grant.opsv-commit-integration-test
  (:require [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.execution-grant.opsv-commit-fixtures :as commit]
            [ai.miniforge.execution-grant.opsv-scope-fixtures :as fixtures]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} current-authority-claims-exact-durable-experiment-once-test
  (doseq [[operation class] [[:trial-apply :effect/deploy] [:start-load :effect/load-test]
                             [:restore-baseline :effect/deploy] [:cleanup-load :effect/load-test]]]
    (fs/with-temp-dir [root {:prefix "opsv-authorized-commit-"}]
      (let [dir (str (fs/canonicalize root))
            scope (fixtures/scope operation)
            g (fixtures/issued class scope)
            t (commit/proposed! dir g scope)
            {:keys [result stored calls]} (commit/current! dir t fixtures/now)
            [claimed persisted] (first calls)]
        (is (grant/valid? (grant/current dir (:grant/id g))))
        (is (= :succeeded (:effect/state result)))
        (is (= result stored))
        (is (= 1 (count calls)))
        (is (= claimed persisted))
        (is (= :committing (:effect/state claimed)))
        (is (= :granted (:effect/authority claimed)))
        (is (= (dissoc scope :effect/id) (:effect/proposal claimed)))
        (is (= (:effect/envelope-id t) (:effect/envelope-id claimed)))
        (is (= :conflict (:anomaly/type (:result (commit/current! dir t fixtures/now)))))))))

(deftest ^{:stratum 0} broad-deployment-authority-cannot-commit-an-experiment-test
  (fs/with-temp-dir [root {:prefix "opsv-broad-grant-"}]
    (let [dir (str (fs/canonicalize root))
          scope (fixtures/scope :trial-apply)
          g (fixtures/issued :effect/deploy (dissoc scope :opsv/experiment))
          t (commit/proposed! dir g scope)
          {:keys [result stored calls]} (commit/current! dir t fixtures/now)]
      (is (= :failed (:effect/state result)))
      (is (= result stored))
      (is (empty? calls)))))

(deftest ^{:stratum 0} persisted-proposal-cannot-substitute-authorized-bindings-test
  (doseq [[path value] [[[:opsv/experiment :verification/id] (random-uuid)]
                        [[:opsv/experiment :runtime/target :workload-uid] "another-deployment"]
                        [[:opsv/experiment :opsv/payload-hash] (fixtures/digest :substituted)]
                        [[:opsv/experiment :comparison/artifact-ref] (random-uuid)]
                        [[:opsv/experiment :opsv/resources 0 :uid] "another-resource"]]]
    (fs/with-temp-dir [root {:prefix "opsv-exact-proposal-"}]
      (let [dir (str (fs/canonicalize root))
            scope (fixtures/scope :trial-apply)
            g (fixtures/issued :effect/deploy scope)
            t (commit/proposed! dir g (assoc-in scope path value))
            {:keys [result stored calls]} (commit/current! dir t fixtures/now)]
        (is (= :failed (:effect/state result)))
        (is (= result stored))
        (is (empty? calls))))))

(deftest ^{:stratum 0} current-durable-revocation-and-clock-refuse-before-provider-test
  (doseq [revoked? [true false]]
    (fs/with-temp-dir [root {:prefix "opsv-live-authority-"}]
      (let [dir (str (fs/canonicalize root))
            scope (fixtures/scope :trial-apply)
            g (fixtures/issued :effect/deploy scope)
            t (commit/proposed! dir g scope)
            clock (if revoked? fixtures/now (.plusNanos fixtures/expiry 1))]
        (when revoked?
          (is (grant/revoked? (grant/revoke-stored! dir (:grant/id g) :revocation/operator clock))))
        (let [{:keys [result stored calls]} (commit/current! dir t clock)]
          (is (= :failed (:effect/state result)))
          (is (= result stored))
          (is (empty? calls)))))))

(deftest ^{:stratum 0} unenforced-path-never-executes-experiments-test
  (doseq [[operation class tagged?] [[:trial-apply :effect/deploy true]
                                    [:start-load :effect/load-test true]
                                    [:start-load :effect/load-test false]]]
    (fs/with-temp-dir [root {:prefix "opsv-no-unenforced-trial-"}]
      (let [dir (str (fs/canonicalize root))
            scope (cond-> (fixtures/scope operation) (not tagged?) (dissoc :opsv/experiment))
            g (fixtures/issued class scope)
            t (commit/proposed! dir g scope)
            {:keys [result stored calls]} (commit/legacy! dir t)]
        (is (= :failed (:effect/state result)))
        (is (= result stored))
        (is (empty? calls))))))

(deftest ^{:stratum 0} ordinary-legacy-effects-retain-compatibility-test
  (doseq [enforced? [true false]]
    (fs/with-temp-dir [root {:prefix "opsv-legacy-compatibility-"}]
      (let [dir (str (fs/canonicalize root))
            scope (dissoc (fixtures/scope :trial-apply) :opsv/experiment)
            g (fixtures/issued :effect/deploy scope)
            t (commit/proposed! dir g scope)
            authority (if enforced? g :authority/unenforced)
            {:keys [result stored calls]} (commit/legacy! dir t authority)]
        (is (= :succeeded (:effect/state result)))
        (is (= (if enforced? :granted :unenforced) (:effect/authority stored)))
        (is (= result stored))
        (is (= [:legacy] calls))))))

(deftest ^{:stratum 0} caller-snapshot-cannot-substitute-for-current-experiment-authority-test
  (doseq [revoked? [true false]]
    (fs/with-temp-dir [root {:prefix "opsv-no-snapshot-authority-"}]
      (let [dir (str (fs/canonicalize root))
            scope (fixtures/scope :trial-apply)
            g (fixtures/issued :effect/deploy scope)
            t (commit/proposed! dir g scope)]
        (when revoked?
          (is (grant/revoked? (grant/revoke-stored! dir (:grant/id g) :revocation/operator fixtures/now))))
        (let [{:keys [result stored calls]} (commit/legacy! dir t g)]
          (is (= :failed (:effect/state result)))
          (is (= result stored))
          (is (empty? calls)))))))

(comment (clojure.test/run-tests 'ai.miniforge.execution-grant.opsv-commit-integration-test))
