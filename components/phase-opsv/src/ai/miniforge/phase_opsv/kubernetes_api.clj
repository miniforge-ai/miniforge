;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-api
  "Fixed native REST group vocabulary shared by acquisition and captured admission.")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} core-api-prefix
  "REST collection prefix for native core/v1 resources."
  "/api/v1")

(def ^{:stratum 0} apps-api-prefix
  "REST collection prefix for apps/v1 workloads."
  "/apis/apps/v1")

(def ^{:stratum 0} cluster-identity-namespace
  "The system Namespace UID binds a logical cluster name to its observed cluster identity."
  "kube-system")

(comment ::core-kubernetes-api-vocabulary)
