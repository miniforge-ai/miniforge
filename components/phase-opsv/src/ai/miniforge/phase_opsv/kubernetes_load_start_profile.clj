;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-start-profile
  "Exact pre-start execution equality with narrowly named native defaults. Unknown fields remain differences."
  (:require [ai.miniforge.phase-opsv.kubernetes-effect-binding :as binding]
            [ai.miniforge.phase-opsv.kubernetes-load-resources :as resources]
            [ai.miniforge.phase-opsv.kubernetes-load-start-defaults :as defaults]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} unadorned? [pod]
  (let [annotations (get-in pod ["metadata" "annotations"])
        finalizers (get-in pod ["metadata" "finalizers"])]
    (and (contains? #{nil {}} annotations)
         (contains? #{nil []} finalizers))))

(defn- ^{:stratum 0} execution-spec [pod]
  (let [normalized (resources/normalized pod)
        spec (merge defaults/pod (get normalized "spec"))
        containers (mapv (partial merge defaults/container) (get spec "containers"))]
    (assoc spec "containers" containers)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} matches? [record pod]
  (let [expected (:body (binding/request record))]
    (and (unadorned? pod)
         (= (get-in expected ["metadata" "labels"]) (get-in pod ["metadata" "labels"]))
         (= (execution-spec expected) (execution-spec pod)))))

(comment
  (unadorned? {"metadata" {"annotations" {"admission/extension" "unsupported"}}}))
