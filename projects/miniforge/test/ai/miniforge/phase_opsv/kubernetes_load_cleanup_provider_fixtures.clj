;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-cleanup-provider-fixtures
  (:require [ai.miniforge.effect-transaction.provider-dispatch-fixtures :as dispatch]
            [ai.miniforge.phase-opsv.boundary.http-transport :as transport]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.kubernetes-load-cleanup-fixtures :as cleanup]
            [ai.miniforge.phase-opsv.kubernetes-load-recovery-provider-fixtures :as recovery]
            [ai.miniforge.phase-opsv.prometheus-test-support :as wire]
            [cheshire.core :as json]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} with-proposed
  "Real retained creation and separately issued cleanup authority."
  (partial recovery/with-proposed cleanup/prepare))

(def ^{:stratum 0} execute-response
  "Execute the exact independently pinned cleanup through native dispatch."
  (partial recovery/execute-response phase/cleanup-kubernetes-load-pod!))

(defn ^{:stratum 0} mutations [calls] (filterv #(= :delete (:method %)) calls))

(defn ^{:stratum 0} pod-response [fixture] (:pod fixture))

(defn ^{:stratum 0} status-response [fixture]
  (let [name (get-in fixture [:pod "metadata" "name"])
        uid (get-in fixture [:pod "metadata" "uid"])
        revision (get-in fixture [:pod "metadata" "resourceVersion"])
        metadata {"resourceVersion" revision}
        details {"name" name "uid" uid "kind" "pods" "group" ""}]
    {"apiVersion" "v1"
     "kind" "Status"
     "status" "Success"
     "metadata" metadata
     "details" details}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} execute
  ([fixture] (execute fixture (pod-response fixture) transport/success-status))
  ([fixture response status]
   (execute-response fixture (wire/completed (wire/response (json/generate-string response) {:status status}))
                     dispatch/dispatch!)))

(comment ::status-details-mirror-native-finalizeDelete-not-a-cleanup-or-baseline-verification-receipt)
