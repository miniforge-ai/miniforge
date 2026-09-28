;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.pr-model-test
  (:require [ai.miniforge.phase-opsv.pr-model :as model]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} one-effect-identity-per-run-and-repository-test
  (let [run (random-uuid)
        id (model/effect-id run "Example/OPSV")]
    (is (uuid? id))
    (is (= id (model/effect-id run "example/opsv")))
    (is (not= id (model/effect-id (random-uuid) "example/opsv")))
    (is (not= id (model/effect-id run "example/other")))))

(deftest ^{:stratum 0} policy-mismatch-is-refused-before-candidate-preparation-test
  (is (= :conflict (:anomaly/type (model/candidate {} {:opsv/policy-hash "verified"}
                                                  {:opsv/policy-hash "other"})))) )

(comment
  (model/effect-id (random-uuid) "example/opsv"))
