;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-start-defaults
  "Exact native server defaults, not allowances for arbitrary admission extensions.")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} noexecute-toleration-seconds
  "The native DefaultTolerationSeconds plugin's standard value; custom values require a new supported profile." 300)

(def ^{:stratum 0} container
  {"terminationMessagePath" "/dev/termination-log"
   "terminationMessagePolicy" "File"})

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} toleration [key]
  {"key" key
   "operator" "Exists"
   "effect" "NoExecute"
   "tolerationSeconds" noexecute-toleration-seconds})

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} pod
  (let [tolerations [(toleration "node.kubernetes.io/not-ready")
                     (toleration "node.kubernetes.io/unreachable")]]
    {"dnsPolicy" "ClusterFirst"
     "schedulerName" "default-scheduler"
     "serviceAccountName" "default"
     "serviceAccount" "default"
     "priority" 0
     "priorityClassName" ""
     "preemptionPolicy" "PreemptLowerPriority"
     "imagePullSecrets" []
     "tolerations" tolerations}))

(comment
  (get pod "schedulerName")
  (get container "terminationMessagePolicy"))
