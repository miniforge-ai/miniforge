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

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} a-settled-deploy-is-reported-not-repeated-test
  (let [context (deploy-context)
        applies (atom 0)]
    (is (= :success (:deploy/status (deploy! context applies (schema/success :stdout "applied")))))
    (let [second (deploy! context applies (schema/success :stdout "applied"))]
      (is (= :success (:deploy/status second)))
      (is (= {:revision "1"} (:deploy/rollback-info second))
          "evidence comes from the durable proposal")
      (is (uuid? (:deploy/grant-id second))))
    (is (= 1 @applies) "the second attempt in the run did not apply again")
    (is (= 1 (count (fx/list-records (:effect-store-dir context)))))))

(deftest ^{:stratum 2} a-failed-deploy-is-retried-under-the-next-id-test
  (let [context (deploy-context)
        applies (atom 0)]
    (deploy! context applies (schema/failure :stdout "apply refused"))
    (is (= :success (:deploy/status (deploy! context applies (schema/success :stdout "applied")))))
    (is (= 2 @applies))
    (is (= #{:failed :reconciled}
           (set (map :effect/state (fx/list-records (:effect-store-dir context))))))))

(deftest ^{:stratum 2} an-unhealthy-rollout-is-observed-again-not-reapplied-test
  (let [context (deploy-context)
        applies (atom 0)
        first-try (deploy! context applies (schema/success :stdout "applied") false)]
    (is (= :failed (:deploy/status first-try)))
    (is (= "rollout timed out" (:deploy/failure first-try)))
    (is (= :success (:deploy/status (deploy! context applies (schema/success :stdout "applied") true))))
    (is (= 1 @applies) "the retry observed the cluster again; it did not apply twice")))
