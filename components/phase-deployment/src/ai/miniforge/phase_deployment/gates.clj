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

(ns ai.miniforge.phase-deployment.gates
  "Deployment-specific gate registrations.

   Gate metadata is loaded from EDN so descriptions and registration stay
   declarative while checks remain focused in code."
  (:require [ai.miniforge.gate.interface :as gate]
            [ai.miniforge.phase-deployment.messages :as msg]
            [ai.miniforge.response.interface :as response]
            [ai.miniforge.schema.interface :as schema]
            [clojure.edn :as edn]
            [clojure.java.io :as io]))

;------------------------------------------------------------------------------ Layer 0
;; Gate schemas + result constructors

(def GateError
  [:map
   [:type keyword?]
   [:message :string]
   [:expected {:optional true} any?]
   [:actual {:optional true} any?]
   [:pod-details {:optional true} [:vector map?]]
   [:url {:optional true} :string]
   [:status-code {:optional true} :int]
   [:command {:optional true} :string]])

(def GateFailure
  [:map
   [:passed? [:= false]]
   [:errors [:vector GateError]]
   [:anomaly map?]])

(def GateDefinition
  [:map
   [:name keyword?]
   [:description-key keyword?]])

(def GateConfig
  [:map
   [:deploy/gates [:vector GateDefinition]]])

(declare gate-definitions)

(defn- validate-result!
  [result-schema result]
  (schema/validate result-schema result))

(defn- gate-error
  ([error-type message-key]
   (gate-error error-type message-key {} nil))
  ([error-type message-key params]
   (gate-error error-type message-key params nil))
  ([error-type message-key params extra]
   (reduce-kv (fn [error-map k v]
                (if (nil? v)
                  error-map
                  (assoc error-map k v)))
              {:type error-type
               :message (msg/t message-key params)}
              (or extra {}))))

(defn- passed
  ([] (passed nil))
  ([extra]
   (merge {:passed? true} extra)))

(defn- failed
  [errors]
  (validate-result!
   GateFailure
   {:passed? false
    :errors errors
    :anomaly (response/gate-anomaly
              :anomalies.gate/validation-failed
              (msg/t :gate/validation-failed)
              errors)}))

(defn- gate-map
  [gate-kw check-fn]
  (let [{:keys [description-key]} (get @gate-definitions gate-kw)]
    {:name gate-kw
     :description (msg/t description-key)
     :check check-fn
     :repair nil}))

(defn- outputs-from-artifact
  [artifact]
  (or (get artifact :outputs)
      (get-in artifact [:content :outputs])
      (get-in artifact [:artifact/content :outputs])
      artifact))

(defn- content-from-artifact
  [artifact]
  (or (get artifact :content) artifact))

(defn- nested-results
  [content direct-key nested-path]
  (if-some [results (or (get content direct-key)
                        (get-in content nested-path))]
    results
    []))

(defn- load-gate-config
  []
  (let [config (edn/read-string (slurp (io/resource "config/phase/deploy-gates.edn")))]
    (schema/validate GateConfig config)))

(def ^:private gate-definitions
  (delay
    (into {}
          (map (juxt :name identity))
          (:deploy/gates (load-gate-config)))))

;------------------------------------------------------------------------------ Layer 1
;; Gate checks

(defn check-provision-validated
  "Validate that provisioning produced expected infrastructure outputs."
  [artifact ctx]
  (let [outputs (outputs-from-artifact artifact)
        expected-keys (get ctx :expected-outputs #{})
        output-keys   (set (keys outputs))
        missing-keys  (vec (remove output-keys expected-keys))]
    (cond
      (or (nil? outputs) (empty? outputs))
      (failed [(gate-error :no-outputs :gate/provision-no-outputs)])

      (seq missing-keys)
      (failed [(gate-error :missing-outputs
                           :gate/provision-missing-outputs
                           {:missing (pr-str missing-keys)}
                           {:expected expected-keys
                            :actual output-keys})])

      :else
      (passed {:output-count (count outputs)
               :output-keys  (vec output-keys)}))))

(defn check-deploy-healthy
  "Validate that all pods are in Ready state after deployment."
  [artifact _ctx]
  (let [pod-state    (content-from-artifact artifact)
        pod-count    (get pod-state :pod-count 0)
        ready-count  (get pod-state :ready-count 0)
        pods         (get pod-state :pods [])
        failing-pods (filterv (complement :ready?) pods)]
    (cond
      (zero? pod-count)
      (failed [(gate-error :no-pods :gate/deploy-no-pods)])

      (not= pod-count ready-count)
      (failed [(gate-error :pods-not-ready
                           :gate/deploy-pods-not-ready
                           {:ready-count ready-count
                            :pod-count pod-count}
                           {:pod-details failing-pods})])

      :else
      (passed {:pod-count pod-count
               :ready-count ready-count}))))

(defn check-health-endpoint
  "Validate that health check results all passed."
  [artifact _ctx]
  (let [content            (content-from-artifact artifact)
        health-results     (nested-results content :health-results [:health :results])
        smoke-results      (nested-results content :smoke-results [:smoke :results])
        failed-health      (remove :passed? health-results)
        failed-smoke       (remove :passed? smoke-results)
        all-health-passed? (or (empty? health-results) (empty? failed-health))
        all-smoke-passed?  (or (empty? smoke-results) (empty? failed-smoke))]
    (cond
      (not all-health-passed?)
      (failed (mapv (fn [result]
                      (gate-error :health-check-failed
                                  :gate/health-http-failed
                                  {:status-code (get result :status-code)}
                                  {:url (get result :url)
                                   :status-code (get result :status-code)}))
                    failed-health))

      (not all-smoke-passed?)
      (failed (mapv (fn [result]
                      (gate-error :smoke-test-failed
                                  :gate/health-command-failed
                                  {}
                                  {:command (get result :command)
                                   :message (get result
                                                 :stderr
                                                 (msg/t :gate/health-command-failed))}))
                    failed-smoke))

      :else
      (passed {:health-checks-passed (count health-results)
               :smoke-tests-passed   (count smoke-results)}))))

;------------------------------------------------------------------------------ Layer 2
;; Registration

(doseq [gate-kw (keys @gate-definitions)]
  (gate/register-gate! gate-kw))

(defmethod gate/get-gate :provision-validated
  [_]
  (gate-map :provision-validated check-provision-validated))

(defmethod gate/get-gate :deploy-healthy
  [_]
  (gate-map :deploy-healthy check-deploy-healthy))

(defmethod gate/get-gate :health-check
  [_]
  (gate-map :health-check check-health-endpoint))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (check-provision-validated {:outputs {:gke_endpoint "https://..." :db_host "10.0.0.1"}} {})
  (check-provision-validated {} {})

  (check-deploy-healthy {:pod-count 3 :ready-count 3 :pods []} {})
  (check-deploy-healthy {:pod-count 3 :ready-count 1 :pods [{:name "p1" :ready? false}]} {})

  (check-health-endpoint {:health-results [{:passed? true :url "http://x/health"}]} {})

  :leave-this-here)
