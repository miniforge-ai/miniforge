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
   [ai.miniforge.phase-deployment.deploy-authority :as authority]
   [ai.miniforge.phase-deployment.deploy-governed :as governed]
   [ai.miniforge.schema.interface :as schema]
   [clojure.test :refer [deftest is]])
  (:import
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} now (Instant/parse "2026-08-01T00:00:00Z"))

(def ^{:stratum 0} rendered-yaml "kind: Deployment\nmetadata: {name: api}\n")

(def ^{:stratum 0} server-dry-run "deployment.apps/api configured (server dry run)")

(def ^{:stratum 0} rollback-info {:revision "4" :image "api@sha256:abc" :replicas 3})

(def ^{:stratum 0} deploy-config
  {:kustomize-dir "/repo/k8s/overlays/prod"
   :namespace "prod"
   :context nil
   :app-label "api"
   :deployment-name "api"
   :phase-config {}})

(defn- ^{:stratum 0} tmp-dir
  []
  (str (.toFile
        (Files/createTempDirectory "deploy" (into-array FileAttribute [])))))

(defn- ^{:stratum 0} record-call
  [calls operation & arguments]
  (swap! calls conj (into [operation] arguments)))

(defn- ^{:stratum 0} applied?
  [calls]
  (some #(= :apply (first %)) @calls))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} context
  []
  {:execution/id (random-uuid)
   :execution/phase-results {:provision {:result {:output "{\"steps\":[]}"}}}
   :effect-store-dir (tmp-dir)
   :grant-breach-dir (tmp-dir)})

(defn- ^{:stratum 1} recording-operations
  [calls]
  {:target! (fn [config]
              (record-call calls :target config)
              (schema/success :target (assoc config :context "gke-prod")))
   :render! (fn [target]
              (record-call calls :render target)
              (schema/success :stdout rendered-yaml))
   :dry-run! (fn [target rendered]
               (record-call calls :dry-run target rendered)
               (schema/success :stdout server-dry-run))
   :rollback-info! (fn [target]
                     (record-call calls :rollback target)
                     (schema/success :rollback-info rollback-info))
   :apply! (fn [target rendered]
             (record-call calls :apply target rendered)
             (schema/success :stdout "applied"))
   :observe! (fn [target]
               (record-call calls :observe target)
               {:provider/matched? true
                :provider/observed {:deployment/ready? true
                                    :deployment/pods
                                    {:pod-count 1 :ready-count 1 :pods []}}})})

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} rejected-dry-run-never-reaches-authority-or-apply-test
  (let [calls (atom [])
        authority-called? (atom false)
        operations (assoc (recording-operations calls)
                          :dry-run! (fn [_ _]
                                      (record-call calls :dry-run)
                                      (schema/failure :stdout "rejected")))]
    (with-redefs [authority/prepare
                  (fn [& _] (reset! authority-called? true))]
      (let [result (governed/transact! (context) deploy-config operations now)]
        (is (= :failed (:deploy/status result)))
        (is (= :preflight (:deploy/stage result)))
        (is (not @authority-called?))
        (is (not (applied? calls)))))))
