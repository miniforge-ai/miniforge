;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.
(ns ai.miniforge.phase-deployment.deploy-governed-test
  "Granted deployment behavior at the provider and durable-record seams."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.effect-transaction.interface :as fx]
   [ai.miniforge.execution-grant.interface :as grant]
   [ai.miniforge.phase-deployment.deploy-authority :as authority]
   [ai.miniforge.phase-deployment.deploy-governed :as governed]
   [ai.miniforge.phase-deployment.policy :as policy]
   [ai.miniforge.phase-deployment.shell :as shell]
   [ai.miniforge.phase-deployment.shell.exec :as exec]
   [ai.miniforge.schema.interface :as schema]
   [clojure.test :refer [deftest is testing]])
  (:import
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} now (Instant/parse "2026-08-01T00:00:00Z"))

(def ^{:stratum 0} rendered-yaml "kind: Deployment\nmetadata: {name: api}\n")

(def ^{:stratum 0} server-dry-run "deployment.apps/api configured (server dry run)")

(def ^{:stratum 0} rollback-info {:revision "4" :image "api@sha256:abc" :replicas 3})

(def ^{:stratum 0} pod-state {:pod-count 1 :ready-count 1 :pods []})

(def ^{:stratum 0} exact-target
  {:kustomize-dir "/repo/k8s/overlays/prod" :namespace "prod"
   :context "gke-prod" :app-label "api"
   :deployment-name "api" :phase-config {}})

(defn- ^{:stratum 0} tmp-dir
  []
  (.getCanonicalPath (.toFile (Files/createTempDirectory "deploy" (into-array FileAttribute [])))))

(defn- ^{:stratum 0} record-call
  [calls operation & arguments]
  (swap! calls conj (into [operation] arguments)))

(defn- ^{:stratum 0} applied?
  [calls]
  (some #(= :apply (first %)) @calls))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} deploy-config
  (assoc exact-target :context nil))

(defn- ^{:stratum 1} context
  []
  {:execution/id (random-uuid)
   :execution/phase-results {:provision {:result {:output {:steps []}}}}
   :effect-store-dir (tmp-dir) :grant-breach-dir (tmp-dir)
   :clock (constantly now)})

(defn- ^{:stratum 1} prepared-authority
  [ctx]
  (authority/prepare
   ctx (random-uuid) exact-target
   {:app-label "api" :rendered-yaml rendered-yaml
    :server-dry-run server-dry-run :rollback-info rollback-info}
   now))

(defn- ^{:stratum 1} recording-operations
  ([calls] (recording-operations calls {}))
  ([calls {:keys [apply-result on-apply observe-result]
           :or {apply-result (schema/success :stdout "applied")
                observe-result {:provider/matched? true
                                :provider/observed {:deployment/ready? true
                                                    :deployment/pods pod-state}}}}]
   {:target! (fn [config]
               (record-call calls :target config)
               (schema/success :target (assoc config :context "gke-prod")))
    :render! (fn [target]
               (record-call calls :render target)
               (with-redefs [exec/sh-with-timeout
                             (fn [& _]
                               (schema/success :stdout rendered-yaml))]
                 (shell/kustomize-render! (:kustomize-dir target))))
    :server-dry-run! (fn [target rendered]
                       (record-call calls :dry-run target rendered)
                       (schema/success :stdout server-dry-run))
    :rollback-info! (fn [target]
                      (record-call calls :rollback target)
                      (schema/success :rollback-info rollback-info))
    :apply-rendered! (fn [target rendered]
                       (record-call calls :apply target rendered)
                       (when on-apply (on-apply))
                       apply-result)
    :observe! (fn [target]
                (record-call calls :observe target)
                observe-result)}))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} rejected-dry-run-never-reaches-authority-or-apply-test
  (let [calls (atom [])
        authority-called? (atom false)
        operations (assoc (recording-operations calls)
                          :server-dry-run! (fn [_ _]
                                             (record-call calls :dry-run)
                                             (schema/failure :stdout "rejected")))]
    (with-redefs [authority/prepare
                  (fn [& _] (reset! authority-called? true))]
      (let [result (governed/transact! (context) deploy-config operations now)]
        (is (= :failed (:deploy/status result)))
        (is (= :preflight (:deploy/stage result)))
        (is (not @authority-called?))
        (is (not (applied? calls)))))))

(deftest ^{:stratum 2} failed-apply-preserves-durable-rollback-evidence-test
  (let [calls (atom [])
        ctx (context)
        operations (recording-operations
                    calls {:apply-result (schema/failure :stdout "apply exploded")})
        result (governed/transact! ctx deploy-config operations now)
        record (first (fx/list-records (:effect-store-dir ctx)))]
    (is (= :failed (:deploy/status result)))
    (is (= rollback-info (:deploy/rollback-info result)))
    (is (= rollback-info
           (get-in record [:effect/observed :deploy/rollback-info])))
    (is (= :revocation/superseded
           (:grant/revocation-reason
            (grant/current (:grant-breach-dir ctx) (:deploy/grant-id result)))))))

(deftest ^{:stratum 2} successful-deploy-records-before-exact-apply-test
  (let [calls (atom [])
        durable-at-apply (atom nil)
        ctx (context)
        operations (recording-operations
                    calls {:on-apply #(reset! durable-at-apply
                                              (first (fx/list-records
                                                      (:effect-store-dir ctx))))})]
    (with-redefs [policy/check-resource-count
                  (fn [_ _] (record-call calls :resource-policy) nil)
                  policy/check-gke-node-limit
                  (fn [_ _] (record-call calls :node-policy) nil)]
      (let [result (governed/transact! ctx deploy-config operations now)
            record (first (fx/list-records (:effect-store-dir ctx)))
            proposal (:effect/proposal record)
            call-names (mapv first @calls)]
        (is (= :success (:deploy/status result)))
        (is (= 1 (count (filter #{:target} call-names))))
        (is (= [:apply (dissoc exact-target :phase-config) rendered-yaml]
               (first (filter #(= :apply (first %)) @calls))))
        (is (< (.indexOf call-names :resource-policy)
               (.indexOf call-names :apply)))
        (is (< (.indexOf call-names :node-policy)
               (.indexOf call-names :apply)))
        (is (= :committing (:effect/state @durable-at-apply)))
        (is (= rendered-yaml (:deploy/rendered-yaml proposal)))
        (is (= server-dry-run (:deploy/server-dry-run proposal)))
        (is (= rollback-info (:deploy/rollback-info proposal)))
        (is (every? some? ((juxt :effect/id :effect/grant-id
                                :effect/envelope-id) @durable-at-apply)))
        (is (= :reconciled (:effect/state record)))
        (is (= {:deployment/ready? true :deployment/pods pod-state}
               (:effect/observed record)))))))

(deftest ^{:stratum 2} inactive-authority-never-reaches-apply-test
  (testing "an absent grant fails before rollback capture"
    (let [calls (atom [])
          ctx (context)
          prepared (assoc (prepared-authority ctx) :authority/grant nil)]
      (with-redefs [authority/prepare (fn [& _] prepared)]
        (let [result (governed/transact! ctx deploy-config
                                         (recording-operations calls) now)]
          (is (= :authority (:deploy/stage result)))
          (is (not (some #(= :rollback (first %)) @calls)))
          (is (not (applied? calls)))))))
  (testing "a grant that has expired by commit time never applies"
    (let [calls (atom [])
          ctx (assoc (context) :clock (constantly (.plusSeconds now 3600)))
          result (governed/transact! ctx deploy-config
                                     (recording-operations calls) now)]
      (is (= :failed (:deploy/status result)))
      (is (not (applied? calls))))))

(deftest ^{:stratum 2} cancelled-run-revokes-before-apply-test
  (let [calls (atom [])
        ctx (assoc (context) :execution/opts
                   {:control-state (atom {:paused false :stopped true})})
        result (governed/transact! ctx deploy-config
                                   (recording-operations calls) now)]
    (is (= :failed (:deploy/status result)))
    (is (not (applied? calls)))
    (is (= :revocation/operator
           (:grant/revocation-reason
            (grant/current (:grant-breach-dir ctx) (:deploy/grant-id result)))))))

(deftest ^{:stratum 2} reconcile-never-moves-the-record-backwards-test
  (let [later (.plusSeconds now 60)
        ctx (assoc (context) :clock (constantly later))
        result (governed/transact! ctx deploy-config
                                   (recording-operations (atom [])) now)
        record (first (fx/list-records (:effect-store-dir ctx)))]
    (is (= :success (:deploy/status result)))
    (is (= later (:effect/updated-at record)))))

(deftest ^{:stratum 2} a-failed-revocation-is-reported-test
  (let [ctx (assoc (context) :execution/opts
                   {:control-state (atom {:paused false :stopped true})})]
    (with-redefs [grant/revoke-stored!
                  (fn [& _] (anomaly/anomaly :unavailable "registry unwritable" {}))]
      (let [result (governed/transact! ctx deploy-config
                                       (recording-operations (atom [])) now)]
        (is (= :failed (:deploy/status result)))
        (is (uuid? (:deploy/grant-id result)))
        (is (anomaly/anomaly? (:deploy/revocation-failure result)))))))

(deftest ^{:stratum 2} a-failed-registration-keeps-the-grant-id-test
  (let [register grant/register!]
    (with-redefs [grant/register!
                  (fn [dir issued]
                    (register dir issued)
                    (anomaly/anomaly :unavailable "publication not confirmed" {}))
                  grant/revoke-stored!
                  (fn [& _] (anomaly/anomaly :unavailable "registry unwritable" {}))]
      (let [result (governed/transact! (context) deploy-config
                                       (recording-operations (atom [])) now)]
        (is (= :authority (:deploy/stage result)))
        (is (uuid? (:deploy/grant-id result)) "the possibly live grant stays identifiable")
        (is (anomaly/anomaly? (:deploy/revocation-failure result)))))))
