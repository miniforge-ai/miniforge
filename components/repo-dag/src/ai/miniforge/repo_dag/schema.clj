(ns ai.miniforge.repo-dag.schema
  "Malli schemas for the repo-dag component.
   Layer 0: Enums and base types
   Layer 1: RepoNode and RepoEdge schemas
   Layer 2: RepoDag composite schema"
  (:require
   [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0
;; Enums and base types

(def repo-types
  "Types of repositories that can be modeled in the DAG."
  [:terraform-module :terraform-live :kubernetes
   :argocd :application :library :documentation])

(def repo-layers
  "Logical layers for repository categorization."
  [:foundations :infrastructure :platform :application :adapters])

(def edge-constraints
  "Constraint types for dependency edges."
  [:module-before-live      ; TF modules before live infra
   :infra-before-k8s        ; Infrastructure before K8s manifests
   :k8s-before-argocd       ; Manifests before ArgoCD apps
   :library-before-consumer ; Libraries before consumers
   :schema-before-impl])    ; Schema changes before implementations

(def merge-orderings
  "Merge ordering strategies for edges."
  [:sequential    ; Must merge in order
   :parallel-ok   ; Can merge in parallel if both ready
   :same-pr-train]) ; Must be in same PR train

(def type->layer
  "Default layer inference from repository type."
  {:terraform-module :foundations
   :terraform-live   :infrastructure
   :kubernetes       :platform
   :argocd           :platform
   :application      :application
   :library          :foundations
   :documentation    :adapters})

(def registry
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

;------------------------------------------------------------------------------ Layer 1
;; RepoNode and RepoEdge schemas

(def WatchConfig
  "Configuration for watching repository changes."
  [:map
   [:labels-include {:optional true} [:vector string?]]
   [:labels-exclude {:optional true} [:vector string?]]
   [:paths-include {:optional true} [:vector string?]]
   [:paths-exclude {:optional true} [:vector string?]]])

(def RepoNode
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

(def EdgeValidation
  "Validation configuration for an edge."
  [:map
   [:require-ci-pass? {:default true} boolean?]
   [:require-plan-clean? {:default false} boolean?]
   [:custom-gate {:optional true} keyword?]])

(def RepoEdge
  "Dependency edge between repositories.
   Represents a directed relationship from one repo to another."
  [:map {:registry registry}
   [:edge/from :repo/name]
   [:edge/to :repo/name]
   [:edge/constraint :edge/constraint]
   [:edge/merge-ordering :edge/merge-ordering]
   [:edge/validation {:optional true} EdgeValidation]])

;------------------------------------------------------------------------------ Layer 2
;; RepoDag composite schema

(def RepoDag
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

(def TopoSortResult
  "Result of a topological sort operation."
  [:map
   [:success boolean?]
   [:order {:optional true} [:vector string?]]
   [:error {:optional true} [:enum :cycle-detected :invalid-dag]]
   [:cycle-nodes {:optional true} [:set string?]]])

(def ValidationResult
  "Result of DAG validation."
  [:map
   [:valid? boolean?]
   [:errors [:vector
             [:map
              [:type [:enum :cycle :orphan-edge :missing-repo :duplicate-repo :self-loop]]
              [:message string?]
              [:data {:optional true} any?]]]]])

;------------------------------------------------------------------------------ Layer 3
;; Validation helpers

(defn valid-repo-node?
  "Check if a value is a valid RepoNode."
  [value]
  (m/validate RepoNode value))

(defn valid-repo-edge?
  "Check if a value is a valid RepoEdge."
  [value]
  (m/validate RepoEdge value))

(defn valid-repo-dag?
  "Check if a value is a valid RepoDag."
  [value]
  (m/validate RepoDag value))

(defn infer-layer
  "Infer the layer from repository type if not explicitly specified."
  [repo-type]
  (get type->layer repo-type :application))

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
