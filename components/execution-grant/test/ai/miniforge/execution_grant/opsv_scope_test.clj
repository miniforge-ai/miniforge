;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.execution-grant.opsv-scope-test
  (:require [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.execution-grant.opsv-scope-fixtures :as fixtures]
            [clojure.test :refer [deftest is]]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} issued fixtures/issued)

(defn- ^{:stratum 0} usage [scope]
  {:effect/scope scope
   :usage/count 1})

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} exact-trial-load-and-recovery-bindings-authorize-test
  (doseq [[operation class] [[:trial-apply :effect/deploy] [:restore-baseline :effect/deploy]
                             [:start-load :effect/load-test] [:stop-load :effect/load-test]
                             [:cleanup-load :effect/load-test]]]
    (let [scope (fixtures/scope operation)
          g (issued class scope)]
      (is (m/validate grant/OpsvExperimentScope scope))
      (is (grant/valid? g))
      (is (grant/authorized? (grant/authorize g (usage scope) fixtures/now))))))

(deftest ^{:stratum 1} generic-deployment-or-unscoped-load-authority-never-becomes-trial-authority-test
  (let [scope (fixtures/scope :trial-apply)
        broad (dissoc scope :opsv/experiment)]
    (doseq [g [(issued :effect/deploy {}) (issued :effect/deploy broad)
               (issued :effect/load-test {})]]
      (is (= :scope-mismatch (:grant/outcome (grant/authorize g (usage scope) fixtures/now)))))
    (is (grant/authorized? (grant/authorize (issued :effect/deploy broad) (usage broad) fixtures/now)))
    (is (= :scope-mismatch (:grant/outcome (grant/authorize (issued :effect/load-test {}) (usage {}) fixtures/now))))
    (is (= :absent (:grant/outcome (grant/authorize nil (usage scope) fixtures/now))))))

(deftest ^{:stratum 1} every-experiment-binding-is-required-and-exact-test
  (let [scope (fixtures/scope :trial-apply)
        binding (:opsv/experiment scope)
        g (issued :effect/deploy scope)]
    (doseq [field (keys binding)]
      (let [missing (update scope :opsv/experiment dissoc field)
            changed (assoc-in scope [:opsv/experiment field] :changed)]
        (is (= :scope-mismatch (:grant/outcome (grant/authorize g (usage missing) fixtures/now))))
        (is (= :scope-mismatch (:grant/outcome (grant/authorize g (usage changed) fixtures/now))))))
    (doseq [field [:workflow-run/id :effect/id]]
      (is (= :scope-mismatch
             (:grant/outcome (grant/authorize g (usage (assoc scope field (random-uuid))) fixtures/now)))))
    (doseq [path [[:runtime/target :workload-uid] [:runtime/target :namespace-uid]
                  [:opsv/load-limits :load/max-requests] [:opsv/resources 0 :name]]]
      (let [changed (assoc-in scope (into [:opsv/experiment] path) :changed)]
        (is (= :scope-mismatch (:grant/outcome (grant/authorize g (usage changed) fixtures/now))))))))

(deftest ^{:stratum 1} scope-cannot-bypass-grant-liveness-count-or-separation-test
  (let [scope (fixtures/scope :start-load)
        g (issued :effect/load-test scope)
        revoked (grant/revoke g :revocation/operator fixtures/now)
        counted (assoc (usage scope) :usage/count 2)]
    (is (= :inactive (:grant/outcome (grant/authorize revoked (usage scope) fixtures/now))))
    (is (grant/authorized? (grant/authorize g (usage scope) fixtures/expiry)))
    (is (= :inactive (:grant/outcome (grant/authorize g (usage scope) (.plusNanos fixtures/expiry 1)))))
    (is (= :exceeded (:grant/outcome (grant/authorize g counted fixtures/now))))
    (doseq [bad [(issued :effect/deploy scope)
                 (issued :effect/load-test scope {:delegable? true})
                 (issued :effect/load-test scope {:constraints {}})]]
      (is (= :scope-mismatch (:grant/outcome (grant/authorize bad (usage scope) fixtures/now)))))
    (let [recovery (fixtures/scope :cleanup-load)]
      (is (= :scope-mismatch (:grant/outcome (grant/authorize g (usage recovery) fixtures/now)))))
    (let [child (assoc g :grant/id (random-uuid) :grant/parent-id (:grant/id g))]
      (is (= :scope-mismatch
             (:grant/outcome (grant/authorize child (usage scope) fixtures/now (constantly g))))))))

(deftest ^{:stratum 1} well-typed-changes-still-require-new-authority-test
  (let [scope (fixtures/scope :trial-apply)
        g (issued :effect/deploy scope)]
    (doseq [[path value] [[[:verification/id] (random-uuid)]
                          [[:candidate/hash] (fixtures/digest :different-candidate)]
                          [[:experiment-pack/hash] (fixtures/digest :different-pack)]
                          [[:comparison/artifact-ref] (random-uuid)]
                          [[:comparison/hash] (fixtures/digest :different-comparison)]
                          [[:runtime/target :workload-uid] "different-deployment-uid"]
                          [[:runtime/target :namespace-uid] "different-namespace-uid"]
                          [[:kubernetes/context] "different-context"]
                          [[:kubernetes/deployment-name] "different-deployment"]
                          [[:opsv/operation] :restore-baseline]
                          [[:opsv/payload-hash] (fixtures/digest :different-payload)]
                          [[:opsv/resources 0 :uid] "different-resource-uid"]
                          [[:opsv/traffic-destinations] ["https://different.invalid/"]]
                          [[:opsv/duration-ms] 120000]
                          [[:opsv/load-limits :load/max-requests] 200]]]
      (let [changed (assoc-in scope (into [:opsv/experiment] path) value)]
        (is (m/validate grant/OpsvExperimentScope changed))
        (is (= :scope-mismatch (:grant/outcome (grant/authorize g (usage changed) fixtures/now))))))))

(deftest ^{:stratum 1} load-and-cleanup-require-explicit-bounds-and-resource-identity-test
  (doseq [[operation path value] [[:start-load [:opsv/traffic-destinations] []]
                                 [:start-load [:opsv/load-limits :load/max-requests] 0]
                                 [:start-load [:opsv/load-limits :load/max-concurrency] 0]
                                 [:start-load [:opsv/load-limits :load/max-rate-per-second] 0]
                                 [:stop-load [:opsv/resources 0 :uid] nil]
                                 [:cleanup-load [:opsv/resources 0 :uid] nil]]]
    (let [scope (assoc-in (fixtures/scope operation) (into [:opsv/experiment] path) value)
          g (issued :effect/load-test scope)]
      (is (= :scope-mismatch (:grant/outcome (grant/authorize g (usage scope) fixtures/now)))))))

(deftest ^{:stratum 1} malformed-generic-scope-remains-a-denial-not-an-exception-test
  (let [g (issued :effect/deploy {})]
    (doseq [scope [nil 42 "opaque"]]
      (is (= :scope-mismatch
             (:grant/outcome (grant/authorize (assoc g :grant/scope scope) (usage {}) fixtures/now)))))))

(deftest ^{:stratum 1} equally-malformed-grant-and-proposal-cannot-authorize-test
  (let [original (fixtures/scope :trial-apply)]
    (doseq [field (keys (:opsv/experiment original))]
      (let [scope (update original :opsv/experiment dissoc field)
            g (issued :effect/deploy scope)]
        (is (= :scope-mismatch (:grant/outcome (grant/authorize g (usage scope) fixtures/now))))))
    (doseq [[field value] [[:environment/class :production] [:candidate/hash "unhashed"]
                           [:unexpected/authority true]]]
      (let [scope (assoc-in original [:opsv/experiment field] value)
            g (issued :effect/deploy scope)]
        (is (= :scope-mismatch (:grant/outcome (grant/authorize g (usage scope) fixtures/now))))))))

(comment (clojure.test/run-tests 'ai.miniforge.execution-grant.opsv-scope-test))
