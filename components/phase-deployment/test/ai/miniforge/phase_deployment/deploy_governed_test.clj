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
  "The seam between a deployment request and a live kubectl apply.

   Every assertion about refusal is really one assertion: `:apply!` was
   never called. A governance seam is only worth its complexity if the
   mutation genuinely does not happen."
  (:require
   [ai.miniforge.phase-deployment.deploy-authority :as authority]
   [ai.miniforge.phase-deployment.deploy-governed :as governed]
   [clojure.test :refer [deftest is testing]])
  (:import
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} now (Instant/parse "2026-08-01T00:00:00Z"))

(def ^{:stratum 0} clean-preview
  "A Pulumi-shaped preview that creates nothing, so both resource checks
   pass and the preflight is allow-class."
  "{\"steps\":[]}")

(defn- ^{:stratum 0} tmp []
  (str (.toFile (Files/createTempDirectory "deploy" (into-array FileAttribute [])))))

(def ^{:stratum 0} deploy-config
  {:kustomize-dir "/repo/k8s/overlays/prod"
   :namespace "prod"
   :context-name "gke-prod"
   :default-context "gke-default"
   :deployment-name "api"})

(defn- ^{:stratum 0} recording-provider
  "Records every provider call. The assertions are about absence."
  [calls & {:keys [rendered apply-failed?]
            :or {rendered clean-preview apply-failed? false}}]
  {:dry-run! (fn [_] (swap! calls conj :dry-run!) rendered)
   :rollback-info! (fn [_] (swap! calls conj :rollback-info!)
                     {:deployment/replicas 3})
   :apply! (fn [_] (swap! calls conj :apply!)
             {:deploy/failed? apply-failed?
              :deploy/failure (when apply-failed? "apply exploded")})
   :observe! (fn [_] (swap! calls conj :observe!)
               {:provider/matched? true
                :provider/observed {:deployment/ready? true}})})

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} context
  ([] (context {}))
  ([overrides]
   (merge {:run-id (random-uuid)
           :effect-store-dir (tmp)
           :grant-breach-dir (tmp)
           :policy-context {}}
          overrides)))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} no-authority-means-no-kubectl-mutation-test
  ;; The headline criterion: "A deploy without an active :effect/deploy
  ;; grant runs no kubectl mutation."
  (testing "a context with no workflow run cannot bind deploy scope"
    (let [calls (atom [])
          result (governed/transact! (dissoc (context) :run-id)
                                     deploy-config
                                     (recording-provider calls)
                                     now)]
      (is (= :failed (:deploy/status result)))
      (is (= :deploy/authority-unavailable (:deploy/refusal result)))
      (is (not (some #{:apply!} @calls))
          (str "no kubectl mutation may run without authority, saw: "
               (pr-str @calls))))))

(deftest ^{:stratum 2} a-denied-preflight-runs-no-mutation-test
  ;; check-resource-count and check-gke-node-limit existed in policy.clj
  ;; and were never consulted before an apply. Now a violation stops it.
  (testing "a preview that creates more than the limit denies the apply"
    (let [creates (apply str (repeat 25 "{\"op\":\"create\",\"type\":\"gcp:Node\"},"))
          preview (str "{\"steps\":[" (subs creates 0 (dec (count creates))) "]}")
          calls (atom [])
          result (governed/transact! (context) deploy-config
                                     (recording-provider calls :rendered preview)
                                     now)]
      (is (= :failed (:deploy/status result)))
      (is (= :deploy/preflight-denied (:deploy/refusal result)))
      (is (not (some #{:apply!} @calls))
          (str "a denied preflight must not reach kubectl, saw: " (pr-str @calls)))))

  (testing "a dry-run that rendered nothing is denied, not waved through"
    (let [calls (atom [])
          result (governed/transact! (context) deploy-config
                                     (recording-provider calls :rendered "")
                                     now)]
      (is (= :deploy/preflight-denied (:deploy/refusal result))
          "we cannot show an unrendered apply is safe")
      (is (not (some #{:apply!} @calls))))))

(deftest ^{:stratum 2} an-authorized-deploy-applies-and-records-test
  ;; A guard that refuses everything is an outage. The allowed path has
  ;; to work, and has to leave evidence.
  (let [calls (atom [])
        ctx (context)
        result (governed/transact! ctx deploy-config
                                   (recording-provider calls) now)]
    (is (some #{:apply!} @calls) "an authorized deploy should reach the provider")
    (is (= :success (:deploy/status result)))
    (is (some? (:deploy/effect-id result)) "the effect id is reported")
    (testing "the dry-run ran BEFORE the apply"
      (is (< (.indexOf ^java.util.List @calls :dry-run!)
             (.indexOf ^java.util.List @calls :apply!))))
    (testing "a durable record exists in the effect store"
      (is (some #(.endsWith (.getName ^java.io.File %) ".edn")
                (file-seq (java.io.File. ^String (:effect-store-dir ctx))))))))

(deftest ^{:stratum 2} rollback-evidence-survives-a-failed-apply-test
  ;; The old store-apply-failure discarded :evidence/rollback-info,
  ;; losing the undo artifact exactly when the apply failed.
  (let [calls (atom [])
        result (governed/transact! (context) deploy-config
                                   (recording-provider calls :apply-failed? true)
                                   now)]
    (is (= :failed (:deploy/status result)))
    (is (some? (:deploy/rollback-info result))
        "rollback evidence must survive the failure path, not only success")))

(deftest ^{:stratum 2} a-denied-envelope-runs-no-mutation-test
  ;; Distinct from authority-UNAVAILABLE. Here authority resolves fine
  ;; and the envelope says no. Without this the permitted? guard is
  ;; untested: removing it fails nothing, because every other refusal
  ;; path short-circuits earlier.
  (let [calls (atom [])
        denied {:effect/id (random-uuid)
                :effect/proposal {}
                :authority/grant {:grant/id (random-uuid)}
                :authority/authorization {:grant/outcome :exceeded}
                :authority/envelope {:envelope/id (random-uuid)
                                     :envelope/decision :deny}}]
    (with-redefs [authority/prepare (fn [& _] denied)]
      (let [result (governed/transact! (context) deploy-config
                                       (recording-provider calls) now)]
        (is (= :deploy/authority-denied (:deploy/refusal result)))
        (is (not (some #{:apply!} @calls))
            (str "a denied envelope must not reach kubectl, saw: "
                 (pr-str @calls)))))))
