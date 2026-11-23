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
   [ai.miniforge.execution-grant.issuance.selection :as selection]
   [ai.miniforge.execution-grant.opsv-profile :as opsv]
   [ai.miniforge.execution-grant.temporal :as temporal]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} pull-request-grant-ttl-seconds
  "Fifteen minutes bounds PR mutation authority while allowing provider latency."
  (* 15 60))

(def ^{:stratum 0} runtime-effect-grant-ttl-seconds
  "Thirty minutes caps provision, deployment, and temporary staging authority."
  (* 30 60))

(defn- ^{:stratum 0} ttl-millis [request policy]
  (if (= :opsv/experiment (selection/profile-key request))
    (get-in request [:opsv/experiment :opsv/duration-ms])
    (.toMillis (java.time.Duration/ofSeconds (:policy/ttl-seconds policy)))))

(defn ^{:stratum 0} admissible-request? [request policy]
  (or (not= :opsv/experiment (selection/profile-key request))
      (and (opsv/admissible? (:effect/class request) request)
           (<= (get-in request [:opsv/experiment :opsv/duration-ms])
               (.toMillis (java.time.Duration/ofSeconds (:policy/ttl-seconds policy)))))))

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
  [ttl-seconds target-scope-keys]
  {:policy/ttl-seconds ttl-seconds
   :policy/scope-keys (into [:effect/id :workflow-run/id] target-scope-keys)
   :policy/constraints {:constraint/max-count 1}
   :policy/delegable? false})

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} policies
  "Explicit issuance policies keyed by effect class or the temporary staging profile."
  {:effect/merge
   (effect-policy pull-request-grant-ttl-seconds [:pr/repo :pr/number :pr/head-sha])

   :effect/pr-create
   (effect-policy pull-request-grant-ttl-seconds
                  [:pr/repo :pr/base :pr/branch :pr/head-sha :pr/payload-hash :pr/governance-hash])

   :effect/push
   (effect-policy pull-request-grant-ttl-seconds
                  [:git/remote-url :git/branch :git/sha :git/force?])

   :effect/comment
   (effect-policy pull-request-grant-ttl-seconds
                  [:pr/repo :pr/number :github/action :github/payload-hash])

   :effect/provision
   (effect-policy runtime-effect-grant-ttl-seconds
                  [:pulumi/stack :pulumi/stack-dir :pulumi/plan-hash])

   :effect/deploy
   (effect-policy runtime-effect-grant-ttl-seconds
                  [:kustomize-dir :context :namespace :deployment-name])

   :opsv/experiment
   (effect-policy runtime-effect-grant-ttl-seconds [:opsv/experiment])})

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} grant-options
  "Derive all grant authority from catalog policy and canonical request."
  [request now]
  (let [effect-class (:effect/class request)
        policy (get policies (selection/profile-key request))
        issued-at (temporal/->instant now)
        principal (workflow-principal (:workflow-run/id request))
        scope (select-keys (normalized-request request) (:policy/scope-keys policy))
        expires-at (.plusMillis issued-at (ttl-millis request policy))]
    {:principal principal
     :effect-class effect-class
     :scope scope
     :constraints (:policy/constraints policy)
     :delegable? (:policy/delegable? policy)
     :expires-at expires-at}))

(comment ::bounded-runtime-owned-issuance-options)
