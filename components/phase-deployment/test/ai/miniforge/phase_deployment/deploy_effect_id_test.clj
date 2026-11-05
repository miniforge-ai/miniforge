;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-deployment.deploy-effect-id-test
  "Deploy attempts take fixed, repeatable effect ids from run, target and
   manifest digest."
  (:require
   [ai.miniforge.effect-transaction.interface :as fx]
   [ai.miniforge.phase-deployment.deploy-governed :as governed]
   [ai.miniforge.phase-deployment.evidence :as evidence]
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
  "Provider operations for `target` rendering `manifest`; counts applies."
  [applies target manifest]
  {:target! (fn [_] (schema/success :target target))
   :render! (fn [_] (schema/success :rendered-yaml manifest))
   :server-dry-run! (fn [_ _] (schema/success :stdout "configured (server dry run)"))
   :rollback-info! (fn [_] (schema/success :rollback-info {:revision "1"}))
   :apply-rendered! (fn [_ _] (swap! applies inc) (schema/success :stdout "applied"))
   :observe! (fn [_] {:provider/matched? true :provider/observed {:deployment/ready? true}})})

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} basis [context manifest]
  [:effect/deploy (:execution/id context) (:kustomize-dir target) (:context target)
   (:namespace target) (:deployment-name target) (evidence/sha256 manifest)])

(defn- ^{:stratum 1} deploy! [context applies manifest]
  (governed/transact! context target (operations applies target manifest) (Instant/now)))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} deploys-take-repeatable-ids-test
  (let [context (deploy-context)
        applies (atom 0)
        manifest "kind: ConfigMap\n"]
    (is (= (fx/attempt-id (basis context manifest) 0)
           (:deploy/effect-id (deploy! context applies manifest))))
    (is (= (fx/attempt-id (basis context manifest) 1)
           (:deploy/effect-id (deploy! context applies manifest)))
        "a retry in the run takes the next attempt and applies again")
    (is (= 2 @applies))))

(deftest ^{:stratum 2} a-changed-manifest-is-a-new-basis-test
  (let [context (deploy-context)
        applies (atom 0)]
    (deploy! context applies "kind: ConfigMap\ndata: {a: '1'}\n")
    (is (= (fx/attempt-id (basis context "kind: ConfigMap\ndata: {a: '2'}\n") 0)
           (:deploy/effect-id (deploy! context applies "kind: ConfigMap\ndata: {a: '2'}\n"))))))
