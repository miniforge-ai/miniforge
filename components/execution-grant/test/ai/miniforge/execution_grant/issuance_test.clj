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
(ns ai.miniforge.execution-grant.issuance-test
  (:require
   [ai.miniforge.execution-grant.eligibility :as eligibility]
   [ai.miniforge.execution-grant.interface :as grant]
   [clojure.test :refer [deftest is testing]])
  (:import
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]
   [java.time Instant]
   [java.util Date UUID]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} now (Instant/parse "2026-08-07T00:00:00Z"))

(def ^{:stratum 0} workflow-run-id
  (UUID/fromString "b4bd9e4f-57ac-40fb-a724-26f4619a47fe"))

(def ^{:stratum 0} effect-id
  (UUID/fromString "0ccdfbe7-cc0b-4f0f-a8a5-23fb6aa7e061"))

(defn- ^{:stratum 0} tmp-dir []
  (str (.toFile (Files/createTempDirectory
                 "grant-issuance"
                 (into-array FileAttribute [])))))

(defn- ^{:stratum 0} request
  [run-id transaction-id effect-class preflight-type target]
  (merge {:workflow-run/id run-id
          :workflow-run/status :running
          :effect/id transaction-id
          :effect/class effect-class
          :effect/preflight {:preflight/type preflight-type
                             :preflight/result :allow}}
         target))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} issue
  ([request] (issue request now))
  ([request issued-at]
   (grant/issue-for-effect (tmp-dir) request issued-at)))

(defn- ^{:stratum 1} breach-record
  [grant-record]
  {:breach/id (random-uuid)
   :breach/principal (:grant/principal grant-record)
   :breach/grant-id (:grant/id grant-record)
   :breach/effect-class (:grant/effect-class grant-record)
   :breach/axis :constraint/max-count
   :breach/limit 1
   :breach/observed 2
   :breach/detection :detected
   :breach/at now})

(def ^{:stratum 1} merge-request
  (request workflow-run-id
           effect-id
           :effect/merge
           :preflight/merge-readiness
           {:pr/repo "miniforge-ai/miniforge"
            :pr/number 1702}))

(def ^{:stratum 1} deploy-request
  (request workflow-run-id
           effect-id
           :effect/deploy
           :preflight/deploy-policy-and-dry-run
           {:kustomize-dir "deploy/overlays/production"
            :context nil
            :default-context "gke_miniforge_production"
            :namespace "miniforge"
            :deployment-name "miniforge-api"}))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} issuer-input-is-closed-and-preflight-gated-test
  (testing "callers cannot supply runtime-owned authority fields"
    (doseq [field [:principal :expires-at :constraints :delegable?]]
      (is (= :invalid-input
             (:anomaly/type (issue (assoc merge-request field :forged)))))))
  (testing "the workflow run must be active"
    (is (= :invalid-input
           (:anomaly/type
            (issue (assoc merge-request :workflow-run/status :completed))))))
  (testing "an absent basis fails the boundary before history is read"
    (let [eligibility-read? (atom false)]
      (with-redefs [eligibility/eligible?
                    (fn [& _] (reset! eligibility-read? true))]
        (is (= :invalid-input
               (:anomaly/type (issue (dissoc merge-request :effect/preflight)))))
        (is (false? @eligibility-read?)))))
  (testing "a deny-class preflight is well formed but unauthorized"
    (is (= :unauthorized
           (:anomaly/type
            (issue (assoc merge-request
                          :effect/preflight
                          (assoc (:effect/preflight merge-request)
                                 :preflight/result :deny))))))))

(deftest ^{:stratum 2} issuer-refuses-incomplete-target-scope-test
  (doseq [field [:pr/repo :pr/number]]
    (is (= :invalid-input
           (:anomaly/type (issue (dissoc merge-request field))))))
  (doseq [field [:kustomize-dir :context :default-context
                 :namespace :deployment-name]]
    (is (= :invalid-input
           (:anomaly/type (issue (dissoc deploy-request field)))))))

(deftest ^{:stratum 2} merge-policy-is-exact-and-runtime-owned-test
  (let [g (issue merge-request)]
    (is (grant/valid? g))
    (is (= :effect/merge (:grant/effect-class g)))
    (is (= (str "runtime:workflow-run/" workflow-run-id)
           (:grant/principal g)))
    (is (= {:effect/id effect-id
            :workflow-run/id workflow-run-id
            :pr/repo "miniforge-ai/miniforge"
            :pr/number 1702}
           (:grant/scope g)))
    (is (= {:constraint/max-count 1} (:grant/constraints g)))
    (is (false? (:grant/delegable? g)))
    (is (= (.plusSeconds now (* 15 60)) (:grant/expires-at g)))))

(deftest ^{:stratum 2} deploy-policy-binds-a-concrete-context-test
  (let [defaulted (issue deploy-request)
        explicit (issue (assoc deploy-request :context "kind-ci"))]
    (is (= "gke_miniforge_production"
           (get-in defaulted [:grant/scope :context])))
    (is (= "kind-ci" (get-in explicit [:grant/scope :context])))
    (is (= {:effect/id effect-id
            :workflow-run/id workflow-run-id
            :kustomize-dir "deploy/overlays/production"
            :context "gke_miniforge_production"
            :namespace "miniforge"
            :deployment-name "miniforge-api"}
           (:grant/scope defaulted)))
    (is (= (.plusSeconds now (* 30 60)) (:grant/expires-at defaulted)))
    (is (= {:constraint/max-count 1} (:grant/constraints defaulted)))
    (is (false? (:grant/delegable? defaulted)))))

(deftest ^{:stratum 2} issuer-calls-root-grant-factory-test
  (let [called (atom nil)
        issue-root grant/issue]
    (with-redefs [grant/issue
                  (fn [options issued-at]
                    (reset! called [options issued-at])
                    (issue-root options issued-at))]
      (is (grant/valid? (issue merge-request)))
      (is (= now (second @called)))
      (is (= :effect/merge (:effect-class (first @called)))))))

(deftest ^{:stratum 2} disqualifying-breach-history-refuses-issuance-test
  (let [dir (tmp-dir)
        first-grant (grant/issue-for-effect dir merge-request now)]
    (grant/record-breach! dir (breach-record first-grant))
    (is (= :unauthorized
           (:anomaly/type
            (grant/issue-for-effect dir merge-request now))))))

(deftest ^{:stratum 2} issuer-validates-its-output-test
  (with-redefs [grant/issue (fn [& _] {:not :a-grant})]
    (is (= :fault (:anomaly/type (issue merge-request))))))

(deftest ^{:stratum 2} date-issuance-time-produces-an-instant-expiry-test
  (let [g (issue merge-request (Date/from now))]
    (is (= now (:grant/issued-at g)))
    (is (= (.plusSeconds now (* 15 60)) (:grant/expires-at g)))))
