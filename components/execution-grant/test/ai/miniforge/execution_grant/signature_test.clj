;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.execution-grant.signature-test
  (:require
   [ai.miniforge.execution-grant.interface :as grant]
   [clojure.test :refer [deftest is]])
  (:import
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} now (Instant/parse "2026-10-29T00:00:00Z"))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} issue-options
  {:principal "runtime:workflow-run/test"
   :effect-class :effect/merge
   :scope {:pr/repo "miniforge-ai/miniforge" :pr/number 7}
   :constraints {:constraint/max-count 1}
   :delegable? true
   :expires-at (.plusSeconds now 600)})

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} issued-grants-carry-a-runtime-signature-test
  (let [g (grant/issue issue-options now)]
    (is (grant/valid? g))
    (is (grant/signed? g))
    (is (not (grant/signed? (assoc-in g [:grant/scope :pr/number] 8))))
    (is (not (grant/signed? (dissoc g :grant/signature))))
    (is (grant/signed? (grant/revoke g :revocation/operator now))
        "revocation does not invalidate the issued signature")))

(deftest ^{:stratum 2} delegated-grants-are-signed-afresh-test
  (let [parent (grant/issue issue-options now)
        child (grant/delegate parent {:principal "agent:reviewer"
                                      :scope (:grant/scope parent)
                                      :constraints {:constraint/max-count 1}
                                      :expires-at (.plusSeconds now 300)}
                              now)]
    (is (grant/signed? child))
    (is (not= (:grant/signature parent) (:grant/signature child)))))
