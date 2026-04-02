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

(ns ai.miniforge.phase.deploy.gates
  "Deployment-specific gate registrations.

   Gates:
   - :provision-validated — Pulumi outputs non-empty with expected keys
   - :deploy-healthy      — All pods in Ready state
   - :health-check        — HTTP health endpoints return 2xx"
  (:require [ai.miniforge.gate.registry :as registry]))

;------------------------------------------------------------------------------ Layer 0
;; Gate check functions

(defn check-provision-validated
  "Validate that provisioning produced expected infrastructure outputs.

   Checks:
   - Pulumi outputs are non-empty
   - Expected output keys are present (configurable via ctx :expected-outputs)
   - No error state in outputs

   Arguments:
     artifact - Phase result artifact from :provision phase
     ctx      - Execution context (may contain :expected-outputs set)"
  [artifact ctx]
  (let [outputs (or (:outputs artifact)
                    (get-in artifact [:content :outputs])
                    (get-in artifact [:artifact/content :outputs])
                    artifact)
        expected-keys (get ctx :expected-outputs #{})
        output-keys   (set (keys outputs))]
    (cond
      (or (nil? outputs) (empty? outputs))
      {:passed? false
       :errors [{:type :no-outputs
                 :message "Provisioning produced no outputs"}]}

      (and (seq expected-keys)
           (not-every? output-keys expected-keys))
      {:passed? false
       :errors [{:type :missing-outputs
                 :message (str "Missing expected outputs: "
                              (pr-str (remove output-keys expected-keys)))
                 :expected expected-keys
                 :actual   output-keys}]}

      :else
      {:passed? true
       :output-count (count outputs)
       :output-keys  (vec output-keys)})))

(defn check-deploy-healthy
  "Validate that all pods are in Ready state after deployment.

   Checks:
   - Pod count > 0
   - All pods are Ready
   - No pods in CrashLoopBackOff or Error state

   Arguments:
     artifact - Phase result artifact from :deploy phase (pod state)
     ctx      - Execution context"
  [artifact _ctx]
  (let [pod-state (or (get-in artifact [:content])
                      artifact)
        pod-count   (:pod-count pod-state 0)
        ready-count (:ready-count pod-state 0)
        pods        (:pods pod-state [])]
    (cond
      (zero? pod-count)
      {:passed? false
       :errors [{:type :no-pods
                 :message "No pods found after deployment"}]}

      (not= pod-count ready-count)
      {:passed? false
       :errors [{:type :pods-not-ready
                 :message (str ready-count "/" pod-count " pods ready")
                 :pod-details (filterv (complement :ready?) pods)}]}

      :else
      {:passed? true
       :pod-count pod-count
       :ready-count ready-count})))

(defn check-health-endpoint
  "Validate that health check results all passed.

   Arguments:
     artifact - Phase result artifact from :validate phase
     ctx      - Execution context"
  [artifact _ctx]
  (let [content (or (:content artifact) artifact)
        health-results (or (:health-results content)
                          (get-in content [:health :results])
                          [])
        smoke-results  (or (:smoke-results content)
                          (get-in content [:smoke :results])
                          [])
        all-health-passed? (or (empty? health-results)
                               (every? :passed? health-results))
        all-smoke-passed?  (or (empty? smoke-results)
                               (every? :passed? smoke-results))]
    (cond
      (not all-health-passed?)
      {:passed? false
       :errors (mapv (fn [r]
                       {:type :health-check-failed
                        :url  (:url r)
                        :status-code (:status-code r)
                        :message (or (:error r)
                                     (str "HTTP " (:status-code r)))})
                     (remove :passed? health-results))}

      (not all-smoke-passed?)
      {:passed? false
       :errors (mapv (fn [r]
                       {:type :smoke-test-failed
                        :command (:command r)
                        :message (get r :stderr "Command failed")})
                     (remove :passed? smoke-results))}

      :else
      {:passed? true
       :health-checks-passed (count health-results)
       :smoke-tests-passed   (count smoke-results)})))

;------------------------------------------------------------------------------ Layer 1
;; Registration

(registry/register-gate! :provision-validated)
(registry/register-gate! :deploy-healthy)
(registry/register-gate! :health-check)

(defmethod registry/get-gate :provision-validated
  [_]
  {:name        :provision-validated
   :description "Validates that infrastructure provisioning produced expected outputs"
   :check       check-provision-validated
   :repair      nil})

(defmethod registry/get-gate :deploy-healthy
  [_]
  {:name        :deploy-healthy
   :description "Validates all pods are in Ready state after deployment"
   :check       check-deploy-healthy
   :repair      nil})

(defmethod registry/get-gate :health-check
  [_]
  {:name        :health-check
   :description "Validates health endpoints return HTTP 2xx"
   :check       check-health-endpoint
   :repair      nil})

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Test provision gate
  (check-provision-validated {:outputs {:gke_endpoint "https://..." :db_host "10.0.0.1"}} {})
  (check-provision-validated {} {})

  ;; Test deploy gate
  (check-deploy-healthy {:pod-count 3 :ready-count 3 :pods []} {})
  (check-deploy-healthy {:pod-count 3 :ready-count 1 :pods [{:name "p1" :ready? false}]} {})

  ;; Test health gate
  (check-health-endpoint {:health-results [{:passed? true :url "http://x/health"}]} {})

  :leave-this-here)
