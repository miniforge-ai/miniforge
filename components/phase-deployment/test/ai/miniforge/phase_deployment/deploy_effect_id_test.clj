;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-deployment.deploy-effect-id-test
  "A deploy's effect id is fixed by run and target, so a second attempt in
   the same run reports the first instead of applying again."
  (:require
   [ai.miniforge.effect-transaction.interface :as fx]
   [ai.miniforge.phase-deployment.deploy-governed :as governed]
   [ai.miniforge.schema.interface :as schema]
   [clojure.test :refer [deftest is]])
  (:import
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} target
  {:kustomize-dir "/repo/k8s/overlays/prod" :namespace "prod" :context "gke-prod"
   :app-label "api" :deployment-name "api" :phase-config {}})

(defn- ^{:stratum 0} deploy-context []
  (let [tmp #(.getCanonicalPath
              (.toFile (Files/createTempDirectory % (into-array FileAttribute []))))]
    {:execution/id (random-uuid)
     :execution/phase-results {:provision {:result {:output {:steps []}}}}
     :effect-store-dir (tmp "attempt-effects")
     :grant-breach-dir (tmp "attempt-grants")}))

(defn- ^{:stratum 0} operations
  "Provider operations for `target`; `apply-result` is what apply returns
   and `ready?` whether the rollout is observed healthy."
  [applies target apply-result ready?]
  {:target! (fn [_] (schema/success :target target))
   :render! (fn [_] (schema/success :rendered-yaml "kind: ConfigMap\n"))
   :server-dry-run! (fn [_ _] (schema/success :stdout "configured (server dry run)"))
   :rollback-info! (fn [_] (schema/success :rollback-info {:revision "1"}))
   :apply-rendered! (fn [_ _] (swap! applies inc) apply-result)
   :observe! (fn [_] {:provider/matched? ready?
                      :provider/observed {:deployment/ready? ready?
                                          :deployment/failure (when-not ready? "rollout timed out")}})})

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} deploy!
  ([context applies apply-result] (deploy! context applies apply-result true))
  ([context applies apply-result ready?]
   (governed/transact! context target (operations applies target apply-result ready?)
                       (Instant/now))))
