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
(ns ai.miniforge.tenancy.interface-test
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.tenancy.interface :as tenancy]
   [clojure.test :refer [deftest is testing]])
  (:import
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} now (Instant/parse "2026-08-01T00:00:00Z"))

(def ^{:stratum 0} configured {:tenancy {:operator-name "chris"}})

(deftest ^{:stratum 0} an-agent-instance-acts-but-cannot-own-test
  ;; :agent-instance is a principal kind and never a tenant kind. That
  ;; asymmetry is what makes a spawned agent containable.
  (is (some #{:agent-instance} tenancy/principal-kinds))
  (is (not (some #{:agent-instance} tenancy/tenant-kinds))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} schemas-are-closed-test
  (let [{:identity/keys [tenant principal]} (tenancy/operator-identity "chris" now)]
    (is (tenancy/valid-tenant? tenant))
    (is (tenancy/valid-principal? principal))
    (testing "unknown keys are rejected — an identity nobody can describe cannot be audited"
      (is (not (tenancy/valid-tenant? (assoc tenant :tenant/extra :x))))
      (is (not (tenancy/valid-principal? (assoc principal :principal/extra :x)))))
    (testing "a blank display name is not a name"
      (is (not (tenancy/valid-tenant? (assoc tenant :tenant/display-name "  ")))))))

(deftest ^{:stratum 1} a-principal-always-belongs-to-a-tenant-test
  ;; An actor nobody can attribute is the state this component exists to
  ;; make unrepresentable.
  (let [{:identity/keys [principal]} (tenancy/operator-identity "chris" now)]
    (is (not (tenancy/valid-principal? (dissoc principal :principal/tenant-id))))
    (is (not (tenancy/valid-principal? (assoc principal :principal/tenant-id nil))))))

(deftest ^{:stratum 1} resolution-comes-from-configuration-test
  (let [resolved (tenancy/resolve-operator configured now)]
    (is (tenancy/valid-identity? resolved))
    (is (= "chris" (get-in resolved [:identity/tenant :tenant/display-name])))
    (is (= :operator (get-in resolved [:identity/tenant :tenant/kind])))
    (is (= :human (get-in resolved [:identity/principal :principal/kind])))
    (testing "the principal belongs to the tenant it was resolved with"
      (is (= (get-in resolved [:identity/tenant :tenant/id])
             (get-in resolved [:identity/principal :principal/tenant-id]))))))

(deftest ^{:stratum 1} ids-are-stable-across-resolutions-test
  ;; A tenant id that changed per process would make yesterday's records
  ;; look like they belong to someone else.
  (is (= (tenancy/resolve-operator configured now)
         (tenancy/resolve-operator configured now)))
  (testing "and stable across differing instants"
    (let [a (tenancy/resolve-operator configured now)
          b (tenancy/resolve-operator configured (Instant/parse "2027-01-01T00:00:00Z"))]
      (is (= (get-in a [:identity/tenant :tenant/id])
             (get-in b [:identity/tenant :tenant/id]))))))

(deftest ^{:stratum 1} no-configured-operator-is-an-error-not-a-default-test
  ;; The refusal is the point. A default tenant would be
  ;; indistinguishable later from a real operator, and every record under
  ;; it would carry an owner that looks observed and is fabricated.
  (doseq [cfg [{} {:tenancy {}} {:tenancy {:operator-name nil}}
               {:tenancy {:operator-name ""}} {:tenancy {:operator-name "   "}}]]
    (let [result (tenancy/resolve-operator cfg now)]
      (is (anomaly/anomaly? result) (str "should refuse: " (pr-str cfg)))
      (is (not (tenancy/valid-identity? result))
          "a refusal must not be mistakable for an identity"))))

(deftest ^{:stratum 1} the-resolver-shape-is-the-seam-test
  ;; A credential-backed resolver drops in behind this shape later. If
  ;; the shape drifts, that becomes a second migration through every
  ;; record-birth site rather than one substitution.
  (let [resolved (tenancy/resolve-operator configured now)]
    (is (= #{:identity/tenant :identity/principal} (set (keys resolved)))
        "exactly one tenant and one principal, nothing else")))
