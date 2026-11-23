;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-volume-scenarios
  (:require [ai.miniforge.phase-opsv.kubernetes-test-support :as kube]
            [ai.miniforge.phase-opsv.kubernetes-volume-fixtures :as wire]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} pod-spec [name]
  (let [base (get (kube/pod) "spec")]
    (-> base
        (assoc "serviceAccountName" "default" "volumes" [(wire/service-account-volume name)])
        (assoc-in ["containers" 0 "volumeMounts"] [(wire/mount name)]))))

(defn ^{:stratum 0} ordinary-volumes []
  (let [base (get (kube/pod) "spec")
        mounts [(assoc (wire/mount "work") "mountPath" "/work")
                (assoc (wire/mount "cache") "mountPath" "/cache")]]
    (-> base
        (assoc "volumes" [(wire/empty-dir "work") (wire/empty-dir "cache")])
        (assoc-in ["containers" 0 "volumeMounts"] mounts))))

(defn ^{:stratum 0} block-device []
  (let [base (get (kube/pod) "spec")
        devices [{"name" "disk"
                  "devicePath" "/dev/catalog"}]]
    (-> base
        (assoc "volumes" [(wire/block-volume "disk")])
        (assoc-in ["containers" 0 "volumeDevices"] devices))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} all-container-groups [name]
  (let [spec (pod-spec name)
        container (get-in spec ["containers" 0])]
    (assoc spec "initContainers" [(assoc container "name" "initialize")]
                "ephemeralContainers" [(assoc container "name" "debug")])))

(comment ::composed-native-pod-scenarios-without-duplicating-wire-shapes)
