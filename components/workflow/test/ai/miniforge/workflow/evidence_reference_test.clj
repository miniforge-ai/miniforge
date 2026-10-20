;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.evidence-reference-test
  (:require [ai.miniforge.workflow.checkpoint-store :as checkpoints]
            [ai.miniforge.workflow.checkpoint-test-support :as fixture]
            [ai.miniforge.workflow.context :as context]
            [ai.miniforge.workflow.execution-lifecycle :as lifecycle]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} workflow []
  {:workflow/id :evidence-reference :workflow/version "1.0.0"
   :workflow/pipeline [{:phase :done}]})

(deftest ^{:stratum 0} only-declared-phase-evidence-is-promoted
  (let [id (random-uuid)
        ctx {:workflow/evidence-bundle-id id}]
    (is (= ctx (lifecycle/record-phase-evidence ctx {:result {}})))
    (is (= {:workflow/evidence-bundle-id nil}
           (lifecycle/record-phase-evidence ctx {:result {:workflow/evidence-bundle-id nil}})))
    (is (= ctx (lifecycle/record-phase-evidence {} {:result (assoc ctx :unrelated true)})))
    (is (= {} (lifecycle/record-phase-evidence {} {:result {:output ctx}})))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-checkpoint [root]
  (let [definition (workflow)
        id (random-uuid)
        opts {:checkpoint/root root}
        ctx (assoc (context/create-context definition {} opts) :workflow/evidence-bundle-id id)]
    (checkpoints/persist-execution-state! ctx)
    (let [saved (checkpoints/load-checkpoint-data (:execution/id ctx) opts)
          snapshot (:machine-snapshot saved)
          resumed (context/restore-context definition {} snapshot {} opts)
          rewound (context/restore-context definition {} snapshot {}
                                           (assoc opts :resume-reset-terminal? true))]
      (is (= id (:workflow/evidence-bundle-id snapshot) (:workflow/evidence-bundle-id resumed)))
      (is (not (contains? rewound :workflow/evidence-bundle-id))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} evidence-reference-survives-resume-but-not-an-explicit-rewind
  (fixture/call-with-temp-checkpoint-root verify-checkpoint))
