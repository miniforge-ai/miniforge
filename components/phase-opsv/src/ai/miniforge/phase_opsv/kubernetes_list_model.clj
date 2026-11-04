;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-list-model
  "Restore omitted list-item TypeMeta only after native list admission.")

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} typed-item [kind resource]
  (let [known-type {"apiVersion" "v1"
                    "kind" kind}]
    (merge known-type resource)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} typed-list [kind resource-list]
  "Restore known TypeMeta defaults only after native list admission; no validation."
  (update resource-list "items" (partial mapv (partial typed-item kind))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} typed-snapshot [snapshot]
  (-> snapshot
      (update :kubernetes/pods (partial typed-list "Pod"))
      (update :kubernetes/nodes (partial typed-list "Node"))))

(comment ::canonical-admitted-list-items)
