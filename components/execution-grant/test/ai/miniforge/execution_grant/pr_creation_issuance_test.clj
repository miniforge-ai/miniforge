;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.execution-grant.pr-creation-issuance-test
  (:require
   [ai.miniforge.execution-grant.interface :as grant]
   [clojure.test :refer [deftest is testing]])
  (:import [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} now (Instant/parse "2026-09-23T00:00:00Z"))

(def ^{:stratum 0} request
  {:workflow-run/id #uuid "b4bd9e4f-57ac-40fb-a724-26f4619a47fe"
   :workflow-run/status :running
   :effect/id #uuid "0ccdfbe7-cc0b-4f0f-a8a5-23fb6aa7e061"
   :effect/class :effect/pr-create
   :effect/preflight {:preflight/type :preflight/pr-create-readiness
                      :preflight/result :allow}
   :pr/repo "example/opsv"
   :pr/base "main"
   :pr/branch "opsv/scaling"
   :pr/head-sha "0123456789012345678901234567890123456789"
   :pr/payload-hash "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"})

(defn- ^{:stratum 0} empty-history-path
  []
  (str (System/getProperty "java.io.tmpdir") "/pr-creation-" (random-uuid)))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} scope
  (dissoc request :workflow-run/status :effect/class :effect/preflight))

(defn- ^{:stratum 1} issue
  [input]
  (grant/issue-for-effect (empty-history-path) input now))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} pr-grant-binds-exact-authority-test
  (let [g (issue request)]
    (is (grant/valid? g))
    (is (= :effect/pr-create (:grant/effect-class g)))
    (is (= scope (:grant/scope g)))
    (is (= (str "runtime:workflow-run/" (:workflow-run/id request))
           (:grant/principal g)))
    (is (= {:constraint/max-count 1} (:grant/constraints g)))
    (is (false? (:grant/delegable? g)))
    (is (= (.plusSeconds now 900) (:grant/expires-at g)))))

(deftest ^{:stratum 2} pr-issuance-refuses-incomplete-or-forged-input-test
  (testing "every scope binding is mandatory and nonempty"
    (doseq [field (keys scope)
            value [nil " " ""]]
      (is (= :invalid-input (:anomaly/type (issue (assoc request field value)))))))
  (testing "missing fields, including preflight, fail at the boundary"
    (doseq [field (keys request)]
      (is (= :invalid-input (:anomaly/type (issue (dissoc request field)))))))
  (testing "authority fields cannot be supplied by callers"
    (doseq [field [:principal :expires-at :constraints :delegable?]]
      (is (= :invalid-input (:anomaly/type (issue (assoc request field :forged)))))))
  (is (= :unauthorized
         (:anomaly/type (issue (assoc-in request [:effect/preflight :preflight/result]
                                        :deny)))))
  (doseq [field [:pr/head-sha :pr/payload-hash]]
    (is (= :invalid-input
           (:anomaly/type (issue (assoc request field "not-a-digest")))))))

(deftest ^{:stratum 2} pr-grant-rechecks-scope-liveness-and-count-test
  (let [g (issue request)
        usage {:effect/scope scope :usage/count 1}
        revoked (grant/revoke g :revocation/operator now)]
    (is (grant/authorized? (grant/authorize g usage now)))
    (is (= :absent (:grant/outcome (grant/authorize nil usage now))))
    (is (= :inactive (:grant/outcome (grant/authorize revoked usage now))))
    (is (= :inactive
           (:grant/outcome (grant/authorize g usage (.plusSeconds now 901)))))
    (is (= :exceeded
           (:grant/outcome (grant/authorize g (assoc usage :usage/count 2) now))))
    (doseq [field (keys scope)]
      (is (= :scope-mismatch
             (:grant/outcome
              (grant/authorize g (assoc-in usage [:effect/scope field] :changed) now)))))))

(comment
  (issue request))
