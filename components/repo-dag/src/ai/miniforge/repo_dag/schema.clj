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
(ns ai.miniforge.repo-dag.schema
  "Malli schemas for the repo-dag component.
   Layer 0: Enums, base types, and standalone map schemas
   Layer 1: Registry and infer-layer (depend on the Layer 0 enums)
   Layer 2: RepoNode and RepoEdge schemas (depend on the registry)
   Layer 3: RepoDag composite schema and node/edge validators
   Layer 4: valid-repo-dag? (depends on RepoDag)"
  (:require
   [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

;; Enums and base types
(def ^{:stratum 0} repo-types
  [:terraform-module :terraform-live :kubernetes
   :argocd :application :library :documentation])

(def ^{:stratum 0} repo-layers
  [:foundations :infrastructure :platform :application :adapters])

(def ^{:stratum 0} edge-constraints
  [:module-before-live      ; TF modules before live infra
   :infra-before-k8s        ; Infrastructure before K8s manifests
   :k8s-before-argocd       ; Manifests before ArgoCD apps
   :library-before-consumer ; Libraries before consumers
   :schema-before-impl])  ; Schema changes before implementations

(def ^{:stratum 0} merge-orderings
  [:sequential    ; Must merge in order
   :parallel-ok   ; Can merge in parallel if both ready
   :same-pr-train])  ; Must be in same PR train

(def ^{:stratum 0} type->layer
  {:terraform-module :foundations
   :terraform-live   :infrastructure
   :kubernetes       :platform
   :argocd           :platform
   :application      :application
   :library          :foundations
   :documentation    :adapters})

;; RepoNode and RepoEdge schemas
(def ^{:stratum 0} WatchConfig
  [:map
   [:labels-include {:optional true} [:vector string?]]
   [:labels-exclude {:optional true} [:vector string?]]
   [:paths-include {:optional true} [:vector string?]]
   [:paths-exclude {:optional true} [:vector string?]]])

(def ^{:stratum 0} EdgeValidation
  [:map
   [:require-ci-pass? {:default true} boolean?]
   [:require-plan-clean? {:default false} boolean?]
   [:custom-gate {:optional true} keyword?]])

(def ^{:stratum 0} TopoSortResult
  "Result of a topological sort operation."
  [:map
   [:success boolean?]
   [:order {:optional true} [:vector string?]]
   [:error {:optional true} [:enum :cycle-detected :invalid-dag]]
   [:cycle-nodes {:optional true} [:set string?]]])

(def ^{:stratum 0} ValidationResult
  "Result of DAG validation."
  [:map
   [:valid? boolean?]
   [:errors [:vector
             [:map
              [:type [:enum :cycle :orphan-edge :missing-repo :duplicate-repo :self-loop]]
              [:message string?]
              [:data {:optional true} any?]]]]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} registry
  "Malli registry for repo-dag schema types."
  {;; Identifiers
   :dag/id          uuid?
   :repo/url        [:string {:min 1}]
   :repo/name       [:string {:min 1}]

   ;; Repo enums
   :repo/type       (into [:enum] repo-types)
   :repo/layer      (into [:enum] repo-layers)

   ;; Edge enums
   :edge/constraint (into [:enum] edge-constraints)
   :edge/merge-ordering (into [:enum] merge-orderings)})

(defn ^{:stratum 1} infer-layer
  [repo-type]
  (get type->layer repo-type :application))

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} RepoNode
  "Repository node in the DAG.
   Represents a single repository with its metadata and configuration."
  [:map {:registry registry}
   [:repo/url :repo/url]
   [:repo/name :repo/name]
   [:repo/org {:optional true} string?]
   [:repo/type :repo/type]
   [:repo/layer :repo/layer]
   [:repo/default-branch {:default "main"} string?]
   [:repo/watch-config {:optional true} WatchConfig]])

(def ^{:stratum 2} RepoEdge
  "Dependency edge between repositories.
   Represents a directed relationship from one repo to another."
  [:map {:registry registry}
   [:edge/from :repo/name]
   [:edge/to :repo/name]
   [:edge/constraint :edge/constraint]
   [:edge/merge-ordering :edge/merge-ordering]
   [:edge/validation {:optional true} EdgeValidation]])

;------------------------------------------------------------------------------ Layer 3

;; RepoDag composite schema
(def ^{:stratum 3} RepoDag
  "Complete repository dependency graph.
   Contains all nodes, edges, and computed ordering information."
  [:map {:registry registry}
   [:dag/id :dag/id]
   [:dag/name [:string {:min 1}]]
   [:dag/description {:optional true} string?]
   [:dag/repos [:vector RepoNode]]
   [:dag/edges [:vector RepoEdge]]
   ;; Computed at runtime
   [:dag/topo-order {:optional true} [:vector :repo/name]]
   [:dag/layers {:optional true} [:map-of keyword? [:vector :repo/name]]]])

;; Validation helpers
(defn ^{:stratum 3} valid-repo-node?
  [value]
  (m/validate RepoNode value))

(defn ^{:stratum 3} valid-repo-edge?
  [value]
  (m/validate RepoEdge value))

;------------------------------------------------------------------------------ Layer 4

(defn ^{:stratum 4} valid-repo-dag?
  [value]
  (m/validate RepoDag value))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Validate RepoNode
  (m/validate RepoNode
              {:repo/url "https://github.com/acme/terraform-modules"
               :repo/name "terraform-modules"
               :repo/org "acme"
               :repo/type :terraform-module
               :repo/layer :foundations
               :repo/default-branch "main"})
  ;; => true

  ;; Validate RepoEdge
  (m/validate RepoEdge
              {:edge/from "terraform-modules"
               :edge/to "terraform-live"
               :edge/constraint :module-before-live
               :edge/merge-ordering :sequential})
  ;; => true

  ;; Validate RepoDag
  (m/validate RepoDag
              {:dag/id (random-uuid)
               :dag/name "acme-infra"
               :dag/repos []
               :dag/edges []})
  ;; => true

  ;; Infer layer
  (infer-layer :terraform-module)
  ;; => :foundations

  (infer-layer :kubernetes)
  ;; => :platform

  :leave-this-here)
