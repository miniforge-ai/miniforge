;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-start-profile-fixtures
  "Independent wire examples from the native defaulting/admission producers, not imported production allowances.")

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} native-toleration [key]
  {"key" key
   "operator" "Exists"
   "effect" "NoExecute"
   "tolerationSeconds" 300})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} server-defaulted [pod]
  (let [tolerations [(native-toleration "node.kubernetes.io/not-ready")
                     (native-toleration "node.kubernetes.io/unreachable")]
        spec {"dnsPolicy" "ClusterFirst"
              "schedulerName" "default-scheduler"
              "serviceAccountName" "default"
              "serviceAccount" "default"
              "priority" 0
              "preemptionPolicy" "PreemptLowerPriority"
              "tolerations" tolerations}
        container {"terminationMessagePath" "/dev/termination-log"
                   "terminationMessagePolicy" "File"}]
    (-> pod
        (update "spec" merge spec)
        (update-in ["spec" "containers" 0] merge container)
        (assoc-in ["spec" "containers" 0 "resources" "limits" "cpu"] "0.1")
        (assoc-in ["spec" "containers" 0 "resources" "requests" "cpu"] "0.1"))))

(comment ::the-real-created-fixture-requests-100m-the-native-equivalent-is-zero-point-one-cpu)
