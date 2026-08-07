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
(ns ai.miniforge.phase-deployment.deploy-config
  "Canonical target and policy inputs for one deployment run."
  (:require
   [ai.miniforge.phase.interface :as phase]
   [ai.miniforge.schema.interface :as schema]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} NonBlankString
  [:and :string [:fn (complement str/blank?)]])

(defn- ^{:stratum 0} merged-phase-config
  [ctx]
  (phase/merge-with-defaults
   (assoc (or (:phase-config ctx) {}) :phase :deploy)))

(defn- ^{:stratum 0} provision-outputs
  [ctx]
  (or (get-in ctx [:execution/phase-results :provision :result :outputs]) {}))

(defn ^{:stratum 0} policy-input
  "The prior provider preview consumed by the established deploy checks."
  [ctx]
  (or (get-in ctx [:execution/phase-results
                   :provision :result :artifact :content])
      {:steps []}))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} DeployRunConfig
  [:map
   [:phase-config map?]
   [:kustomize-dir NonBlankString]
   [:namespace NonBlankString]
   [:app-label NonBlankString]
   [:deployment-name NonBlankString]
   [:context NonBlankString]
   [:default-context NonBlankString]])

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} resolve-config
  "Resolve one closed deployment target, including an explicit Kube context."
  [ctx]
  (let [phase-config (merged-phase-config ctx)
        input (or (:execution/input ctx) {})
        outputs (provision-outputs ctx)
        requested-context (or (:context input)
                              (:context phase-config)
                              (:gke_context outputs))
        default-context (or (:default-context input)
                            (:default-context phase-config)
                            requested-context)
        app-label (get input :app-label
                       (get phase-config :app-label "ixi"))]
    (schema/validate-anomaly
     DeployRunConfig
     {:phase-config phase-config
      :kustomize-dir (or (:kustomize-dir input)
                         (:kustomize-dir phase-config))
      :namespace (get input :namespace
                      (get phase-config :namespace "default"))
      :context (or requested-context default-context)
      :default-context default-context
      :app-label app-label
      :deployment-name (get input :deployment-name
                            (get phase-config :deployment-name app-label))})))
