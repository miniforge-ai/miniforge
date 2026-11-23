;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-quiescence-fixtures
  (:require [ai.miniforge.phase-opsv.boundary.http-transport :as http]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.prometheus-test-support :as wire]
            [cheshire.core :as json]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} terminated-pod [pod]
  (let [id "containerd://actual-load-container"
        terminated {"exitCode" 0 "reason" "Completed" "containerID" id
                    "startedAt" "2026-11-08T01:00:00Z" "finishedAt" "2026-11-08T01:01:00Z"}
        status {"name" "load" "restartCount" 0 "containerID" id "state" {"terminated" terminated}}]
    (-> pod
        (update "spec" dissoc "schedulingGates")
        (assoc-in ["spec" "nodeName"] "worker-node")
        (assoc "status" {"phase" "Succeeded" "containerStatuses" [status]}))))

(defn- ^{:stratum 0} respond! [calls pending options]
  (swap! calls conj options)
  pending)

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} observe-response [{:keys [runtime plan]} pending]
  (let [calls (atom [])]
    (with-redefs [http/start! (partial respond! calls pending) http/response-fields identity]
      [(phase/read-kubernetes-load-quiescence! runtime plan) @calls])))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} observe [fixture pod]
  (observe-response fixture (wire/completed (wire/response (json/generate-string pod)))))

(comment ::only-http-is-simulated-durable-ownership-is-established-by-the-real-creation-fixture)
