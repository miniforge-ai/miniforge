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
(ns ai.miniforge.phase-deployment.deploy-authority-test
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.execution-grant.interface :as grant]
   [ai.miniforge.phase-deployment.deploy-authority :as authority]
   [ai.miniforge.phase-deployment.policy :as policy]
   [clojure.test :refer [deftest is]])
  (:import
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} now (Instant/parse "2026-08-01T00:00:00Z"))

(def ^{:stratum 0} run-id (random-uuid))

(def ^{:stratum 0} target
  {:kustomize-dir "/repo/k8s"
   :context "gke-prod"
   :namespace "prod"
   :deployment-name "api"
   :phase-config {}})

(def ^{:stratum 0} provision-preview
  {:steps []})

(def ^{:stratum 0} preflight
  {:app-label "api"
   :rendered-yaml "manifest"
   :server-dry-run "validated"
   :rollback-info {:revision "3"}})

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} context
  []
  {:execution/id run-id
   :execution/phase-results {:provision {:result {:output provision-preview}}}
   :grant-breach-dir
   (str (.toFile
         (Files/createTempDirectory "grant" (into-array FileAttribute []))))})

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} request-binds-canonical-authority-inputs-test
  (let [request (authority/request (context) (random-uuid) target)]
    (is (= run-id (:workflow-run/id request)))
    (is (= "gke-prod" (:context request)))
    (is (= "gke-prod" (:default-context request)))))

(deftest ^{:stratum 2} prepare-rejects-legacy-only-authority-inputs-test
  (let [legacy-context (-> (context) (dissoc :execution/id)
                           (assoc :run-id run-id))
        legacy-target (-> target (dissoc :context)
                          (assoc :context-name "legacy"))
        prepared (authority/prepare legacy-context (random-uuid) legacy-target
                                    preflight now)]
    (is (anomaly/anomaly? prepared))
    (is (= :invalid-input (:anomaly/type prepared)))
    (is (= [:execution/id :context]
           (get-in prepared [:anomaly/data :authority/missing-fields])))))

(deftest ^{:stratum 2} prepare-omits-unrecorded-preflight-evidence-test
  (let [proposal (:effect/proposal
                  (authority/prepare (context) (random-uuid) target {} now))]
    (is (not-any? #(contains? proposal %)
                  [:app-label :deploy/rendered-yaml
                   :deploy/server-dry-run :deploy/rollback-info]))))

(deftest ^{:stratum 2} prepare-denies-when-provision-preview-is-absent-test
  (let [prepared (authority/prepare
                  (dissoc (context) :execution/phase-results)
                  (random-uuid) target preflight now)]
    (is (not (authority/permitted? prepared)))
    (is (= :deny (get-in prepared [:authority/envelope :envelope/decision])))
    (is (= [:deploy/provision-preview-required]
           (mapv :rule-id (get-in prepared [:authority/policy :blocking]))))))

(deftest ^{:stratum 2} prepare-denies-policy-violations-test
  (with-redefs [policy/check-resource-count
                (fn [& _] {:violation/rule-id :deploy/resource-count-limit
                           :violation/message "limit exceeded"})
                policy/check-gke-node-limit (constantly nil)
                grant/issue-for-effect
                (fn [& _] (throw (ex-info "grant must not be issued" {})))]
    (let [prepared (authority/prepare (context) (random-uuid)
                                      target preflight now)]
      (is (not (authority/permitted? prepared)))
      (is (nil? (:authority/grant prepared)))
      (is (= [:deploy/resource-count-limit]
             (mapv :rule-id
                   (get-in prepared [:authority/policy :blocking])))))))

(deftest ^{:stratum 2} prepare-records-policy-and-preflight-basis-test
  (let [checked (atom [])]
    (with-redefs [policy/check-resource-count
                  (fn [preview _] (swap! checked conj [:resources preview]) nil)
                  policy/check-gke-node-limit
                  (fn [preview _] (swap! checked conj [:nodes preview]) nil)]
      (let [prepared (authority/prepare (context) (random-uuid)
                                        target preflight now)]
        (is (authority/permitted? prepared))
        (is (not (authority/permitted? (assoc prepared :authority/grant nil))))
        (is (= [[:resources provision-preview] [:nodes provision-preview]]
               @checked))
        (is (= "manifest"
               (get-in prepared [:effect/proposal :deploy/rendered-yaml])))
        (is (= "validated"
               (get-in prepared [:effect/proposal :deploy/server-dry-run])))
        (is (every? some?
                    ((juxt :effect/id
                           #(get-in % [:authority/grant :grant/id])
                           #(get-in % [:authority/envelope :envelope/id]))
                     prepared)))))))
