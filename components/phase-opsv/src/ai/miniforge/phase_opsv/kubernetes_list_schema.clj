;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-list-schema
  "Native list items may omit TypeMeta; explicit contradictory types remain invalid."
  (:require [ai.miniforge.phase-opsv.kubernetes-resource-schema :as resource]
            [malli.util :as mu]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} native-item [typed-resource]
  (let [fields (reduce mu/merge (rest typed-resource))]
    (mu/optional-keys fields ["apiVersion" "kind"])))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} PodItem (native-item resource/Pod))

(def ^{:stratum 1} NodeItem (native-item resource/Node))

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} PodList
  [:map ["apiVersion" [:= "v1"]] ["kind" [:= "PodList"]]
   ["items" [:vector {:min 1} PodItem]]])

(def ^{:stratum 2} NodeList
  [:map ["apiVersion" [:= "v1"]] ["kind" [:= "NodeList"]]
   ["items" [:vector {:min 1} NodeItem]]])

(comment ::native-kubernetes-list-contracts)
