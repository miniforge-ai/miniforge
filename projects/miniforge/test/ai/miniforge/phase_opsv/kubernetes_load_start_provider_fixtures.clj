;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-start-provider-fixtures
  (:require [ai.miniforge.effect-transaction.provider-dispatch-fixtures :as dispatch]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.kubernetes-load-recovery-provider-fixtures :as owned]
            [ai.miniforge.phase-opsv.kubernetes-owned-load-request-fixtures :as request]
            [ai.miniforge.phase-opsv.prometheus-test-support :as wire]
            [cheshire.core :as json]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} running [fixture]
  (assoc fixture :workflow-run/status :running))

(def ^{:stratum 0 :private true} prepare
  (partial request/prepare phase/prepare-kubernetes-load-start-proposal!))

(def ^{:stratum 0} execute-response
  "Commit using the actual separately issued forward grant and independent pin."
  (partial owned/execute-response phase/start-kubernetes-load-pod!))

(defn ^{:stratum 0} mutations [calls]
  (filterv #(= :patch (:method %)) calls))

(defn ^{:stratum 0} response [pod]
  (-> pod
      (update "spec" dissoc "schedulingGates")
      (assoc-in ["metadata" "resourceVersion"] "acknowledged-gate-release")))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} with-proposed
  "Real retained approval and creation with fresh forward, not recovery, authority."
  (partial owned/with-proposed prepare running))

(defn ^{:stratum 1} execute
  ([fixture] (execute fixture (response (:pod fixture))))
  ([fixture pod]
   (execute-response fixture (wire/completed (wire/response (json/generate-string pod))) dispatch/dispatch!)))

(comment
  (response {"spec" {"schedulingGates" []}}))
