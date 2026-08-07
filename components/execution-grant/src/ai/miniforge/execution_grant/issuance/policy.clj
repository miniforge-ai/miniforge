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
(ns ai.miniforge.execution-grant.issuance.policy
  "Runtime-owned policies for grants that commit irreversible effects."
  (:require
   [ai.miniforge.execution-grant.temporal :as temporal]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} workflow-principal
  "Derive the runtime principal; callers cannot choose a grant principal."
  [workflow-run-id]
  (str "runtime:workflow-run/" workflow-run-id))

(defn- ^{:stratum 0} normalized-request
  "Bind a nil deploy context to configuration rather than a wildcard."
  [{:effect/keys [class] :as request}]
  (if (= :effect/deploy class)
    (update request :context #(or % (:default-context request)))
    request))

(defn- ^{:stratum 0} effect-policy
  "Construct one policy entry while keeping shared authority in one place."
  [ttl-seconds target-scope-keys]
  {:policy/ttl-seconds ttl-seconds
   :policy/scope-keys (into [:effect/id :workflow-run/id] target-scope-keys)
   :policy/constraints {:constraint/max-count 1}
   :policy/delegable? false})

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} policies
  "Explicit issuance policy catalog keyed by irreversible effect class."
  {:effect/merge
   (effect-policy (* 15 60) [:pr/repo :pr/number])

   :effect/deploy
   (effect-policy (* 30 60)
                  [:kustomize-dir :context :namespace :deployment-name])})

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} grant-options
  "Derive all grant authority from catalog policy and canonical request."
  [request now]
  (let [effect-class (:effect/class request)
        policy (get policies effect-class)
        issued-at (temporal/->instant now)]
    {:principal (workflow-principal (:workflow-run/id request))
     :effect-class effect-class
     :scope (select-keys (normalized-request request)
                         (:policy/scope-keys policy))
     :constraints (:policy/constraints policy)
     :delegable? (:policy/delegable? policy)
     :expires-at (.plusSeconds issued-at (:policy/ttl-seconds policy))}))
