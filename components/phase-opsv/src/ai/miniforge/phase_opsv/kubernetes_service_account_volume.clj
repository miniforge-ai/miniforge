;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-service-account-volume)

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} stable-binding
  "Non-native identifier for the single recognized admission-generated token volume."
  :kubernetes/service-account-volume)

(def ^{:stratum 0} ^:private generated-name
  "Kubernetes service-account admission's generated prefix and five-character suffix."
  #"\Akube-api-access-[a-z0-9]{5}\z")

(def ^{:stratum 0} ^:private native-source-kinds
  "Ordered, exclusive native sources; unknown or additional source kinds stay literal."
  [#{"serviceAccountToken"} #{"configMap"} #{"downwardAPI"}])

(def ^{:stratum 0} ^:private native-files
  "Admission's fixed file bindings; modes, audiences, lifetimes and all contents remain untouched."
  {["projected" "sources" 0 "serviceAccountToken" "path"] "token"
   ["projected" "sources" 1 "configMap" "name"] "kube-root-ca.crt"
   ["projected" "sources" 1 "configMap" "items"] [{"key" "ca.crt"
                                                    "path" "ca.crt"}]
   ["projected" "sources" 2 "downwardAPI" "items"]
   [{"path" "namespace"
     "fieldRef" {"apiVersion" "v1"
                 "fieldPath" "metadata.namespace"}}]})

(def ^{:stratum 0} ^:private token-mount-path
  "Only the admission-generated read-only service-account mount can share the stable binding."
  "/var/run/secrets/kubernetes.io/serviceaccount")

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} native-mount? [reference]
  (and (= token-mount-path (get reference "mountPath"))
       (true? (get reference "readOnly"))
       (not (contains? reference "devicePath"))))

(defn ^{:stratum 1} generated? [volume]
  (let [sources (get-in volume ["projected" "sources"])]
    (and (re-matches generated-name (get volume "name"))
         (= #{"name" "projected"} (set (keys volume)))
         (= native-source-kinds (mapv (comp set keys) sources))
         (every? (fn [[path expected]] (= expected (get-in volume path))) native-files))))

(comment ::recognition-is-narrow-and-never-drops-source-or-mount-content)
