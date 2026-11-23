;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-replica-set-reference
  (:require [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private native-replica-set-groups
  "Native ReplicaSet families, including historical groups; version never removes the parent-resolution requirement."
  #{"apps" "extensions"})

(defn- ^{:stratum 0} api-group [reference]
  (some-> (get reference "apiVersion") (str/split #"/") first))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} native-replica-set? [reference]
  (and (= "ReplicaSet" (get reference "kind"))
       (contains? native-replica-set-groups (api-group reference))))

(comment ::legacy-native-replica-set-groups)
