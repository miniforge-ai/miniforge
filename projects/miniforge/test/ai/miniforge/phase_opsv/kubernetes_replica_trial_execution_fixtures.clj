;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-replica-trial-execution-fixtures
  (:require [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.execution-grant.opsv-scope-fixtures :as clock]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.kubernetes-forward-execution-fixtures :as forward]
            [ai.miniforge.phase-opsv.kubernetes-forward-execution-wire-fixtures :as wire]
            [ai.miniforge.phase-opsv.kubernetes-inventory-fixtures :as inventory]
            [ai.miniforge.phase-opsv.kubernetes-replica-trial-fixtures :as trial]
            [ai.miniforge.phase-opsv.prometheus-test-support :as http]
            [cheshire.core :as json]
            [clojure.test :refer [is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} committed-revision
  "Server-assigned revision after the conditional candidate patch."
  "committed-trial-revision")

(defn ^{:stratum 0} mutations [calls] (filterv #(= :patch (:method %)) calls))

(defn- ^{:stratum 0} material? [kind value]
  (= kind (get-in value [:artifact/metadata :opsv/material-kind])))

(defn ^{:stratum 0} current-grant [{:keys [host/runtime] :as fixture}]
  (let [id (get-in (first (forward/dispositions fixture)) [:opsv/governed-effect :evidence/grant-id])]
    (grant/current (:authority-directory runtime) id)))

(defn ^{:stratum 0} disabled [fixture]
  (swap! (:host/context fixture) update :execution/opts dissoc :opsv/experiment-execution)
  fixture)

(defn- ^{:stratum 0} stop! [fixture]
  (phase/stop-supervised-runs! (:host/supervisor fixture) clock/now))

(defn ^{:stratum 0} execute-response [fixture pending on-read!]
  (wire/execute-response phase/execute-kubernetes-replica-trial! fixture pending on-read!))

(defn- ^{:stratum 0} configured [f fixture]
  (let [request (:trial/request fixture)
        [proposal _] (trial/prepare fixture (:trial/routes fixture))]
    (is (map? (:kubernetes/request proposal)))
    (forward/with-runtime f (assoc fixture :request request :proposal proposal :host/routes (:trial/routes fixture)))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} material-artifact [kind {:keys [ctx] :as fixture}]
  (let [directory (get-in ctx [:execution/opts :opsv/artifact-directory])
        ids (:opsv/artifact-refs (forward/assembly fixture))
        values (map (partial artifact/read-published directory) ids)]
    (first (filter (partial material? kind) values))))

(defn- ^{:stratum 1} native-response [fixture]
  (let [deployment (get-in fixture [:host/routes inventory/deployment-path])
        count (get-in fixture [:request :replicas/count])]
    (-> deployment
        (assoc-in ["metadata" "resourceVersion"] committed-revision)
        (assoc-in ["spec" "replicas"] count))))

(defn- ^{:stratum 1} revoke! [fixture]
  (let [id (:grant/id (current-grant fixture))
        runtime (:host/runtime fixture)]
    (grant/revoke-stored! (:authority-directory runtime) id :revocation/operator ((:clock runtime)))))

(defn- ^{:stratum 1} expire! [fixture]
  (let [expires (:grant/expires-at (current-grant fixture))]
    (reset! (:host/clock fixture) (.plusNanos expires 1))))

(defn ^{:stratum 1} with-executable [f] (trial/with-input (partial configured f)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} readiness-artifact [fixture]
  (material-artifact :native-replica-trial-readiness fixture))

(defn ^{:stratum 2} execute
  ([fixture] (execute fixture (constantly nil)))
  ([fixture on-read!]
   (let [pending (-> (native-response fixture) json/generate-string http/response http/completed)]
     (execute-response fixture pending on-read!))))

(defn ^{:stratum 2} change! [change fixture]
  (case change
    :policy (forward/deny-policy! fixture)
    :stop (stop! fixture)
    :revoke (revoke! fixture)
    :expiry (expire! fixture)))

(comment
  (with-executable execute))
