;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-recovery-execution-fault-fixtures
  (:require [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.phase-opsv.kubernetes-capture-test-support :as capture]
            [ai.miniforge.phase-opsv.kubernetes-load-recovery-execution-fixtures :as fixtures]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} constructed-owned-pod-read-count
  "Initial readiness and canonical construction precede the fresh pre-issuance ownership read."
  2)

(defn- ^{:stratum 0} changed-response! [path value fixture reply! routes calls options]
  (let [runtime (:host/runtime fixture)
        id (get-in fixture [:request :effect/id])
        record (effect/read-record (:effects-directory runtime) id)
        claimed? (= :committing (:effect/state record))
        current (if claimed? (assoc-in routes path value) routes)]
    (reply! current calls options)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} pregrant-response! [fixture reply! routes calls options]
  (let [path (:load/path fixture)
        observed (count (filter #(= path (capture/path %)) @calls))
        changed? (>= observed constructed-owned-pod-read-count)
        current (if changed? (assoc-in routes [path "metadata" "resourceVersion"] "changed-before-issuance") routes)]
    (reply! current calls options)))

(defn ^{:stratum 1} execute-with-drift [operation path value fixture]
  (let [reply! capture/reply!]
    (with-redefs [capture/reply! (partial changed-response! path value fixture reply!)]
      (fixtures/execute operation fixture))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} execute-with-pregrant-drift [operation fixture]
  (let [reply! capture/reply!]
    (with-redefs [capture/reply! (partial pregrant-response! fixture reply!)]
      (fixtures/execute operation fixture))))

(comment ::late-native-drift-follows-the-real-durable-claim-not-a-mocked-admission)
