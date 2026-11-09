;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-creation-execution-fixtures
  (:require [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.kubernetes-forward-execution-fixtures :as forward]
            [ai.miniforge.phase-opsv.kubernetes-forward-execution-wire-fixtures :as wire]
            [ai.miniforge.phase-opsv.kubernetes-load-provider-fixtures :as native]
            [ai.miniforge.phase-opsv.kubernetes-replica-provider-fixtures :as provider]
            [ai.miniforge.phase-opsv.recovery-plan-retention-fixtures :as retained]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} claimed-target-read-count
  "Two physical roots for initial readiness, after construction, and inside the claimed dispatch." 6)

(def ^{:stratum 0} beyond-grant-lifetime-seconds 1801)

(def ^{:stratum 0} initial-root-read-count 2)

(defn ^{:stratum 0} posts [calls] (filterv #(= :post (:method %)) calls))

(defn ^{:stratum 0} disabled [fixture]
  (swap! (:host/context fixture) update :execution/opts dissoc :opsv/experiment-execution)
  fixture)

(defn ^{:stratum 0} revoke-current-grant! [{:keys [host/runtime] :as fixture}]
  (let [id (get-in (first (forward/dispositions fixture)) [:opsv/governed-effect :evidence/grant-id])]
    (grant/revoke-stored! (:authority-directory runtime) id :revocation/operator ((:clock runtime)))))

(defn ^{:stratum 0} execute-response [fixture pending on-read!]
  (wire/execute-response phase/execute-kubernetes-load-creation! fixture pending on-read!))

(defn- ^{:stratum 0} configured [f {:keys [input plan-id native ctx] :as fixture}]
  (let [request {:recovery/input input
                 :recovery/artifact-ref plan-id
                 :creation/provider (provider/settings)}
        proposal (phase/prepare-governed-kubernetes-load-proposal! ctx (:load input))]
    (forward/with-runtime f (assoc fixture :request request :proposal proposal :host/routes (:routes native)))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} execute
  ([fixture] (execute fixture (constantly nil)))
  ([fixture on-read!] (execute-response fixture (native/response fixture) on-read!)))

(defn ^{:stratum 1} with-creatable [f] (retained/with-retained (partial configured f)))

(comment
  (with-creatable execute))
