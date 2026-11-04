;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-volume-fixtures)

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} token-expiration-seconds
  "Captured service-account token lifetime; projection must preserve it exactly."
  3607)

(def ^{:stratum 0} volume-mode
  "Native decimal representation of mode 0644; never normalized by the projection."
  420)

(defn ^{:stratum 0} mount [name]
  {"name" name
   "readOnly" true
   "mountPath" "/var/run/secrets/kubernetes.io/serviceaccount"})

(defn ^{:stratum 0} empty-dir [name]
  {"name" name
   "emptyDir" {}})

(defn ^{:stratum 0} block-volume [name]
  {"name" name
   "persistentVolumeClaim" {"claimName" "catalog-data"}})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} service-account-volume [name]
  {"name" name
   "projected" {"defaultMode" volume-mode
                "sources" [{"serviceAccountToken" {"path" "token"
                                                   "expirationSeconds" token-expiration-seconds}}
                           {"configMap" {"name" "kube-root-ca.crt"
                                         "items" [{"key" "ca.crt"
                                                   "path" "ca.crt"}]}}
                           {"downwardAPI" {"items" [{"path" "namespace"
                                                     "fieldRef" {"apiVersion" "v1"
                                                                 "fieldPath" "metadata.namespace"}}]}}]}})

(comment ::native-service-account-admission-shape-with-no-token-values)
