(ns ai.miniforge.repo-dag.interface
  "Public API for the repo-dag component.
   Provides DAG CRUD, topological sorting, and dependency graph queries."
  (:require
   [ai.miniforge.repo-dag.core :as core]
   [ai.miniforge.repo-dag.schema :as schema]))

;------------------------------------------------------------------------------ Layer 0
;; Schema re-exports

(def RepoNode schema/RepoNode)
(def RepoEdge schema/RepoEdge)
(def RepoDag schema/RepoDag)
(def WatchConfig schema/WatchConfig)
(def EdgeValidation schema/EdgeValidation)

;; Enum value sets for programmatic access
(def repo-types schema/repo-types)
(def repo-layers schema/repo-layers)
(def edge-constraints schema/edge-constraints)
(def merge-orderings schema/merge-orderings)
(def type->layer schema/type->layer)

;; Validation helpers
(def valid-repo-node? schema/valid-repo-node?)
(def valid-repo-edge? schema/valid-repo-edge?)
(def valid-repo-dag? schema/valid-repo-dag?)
(def infer-layer schema/infer-layer)

;------------------------------------------------------------------------------ Layer 1
;; Manager lifecycle

(defn create-manager
  "Create a new in-memory DAG manager.

   Returns a manager instance that can be used with all other functions.

   Example:
     (def mgr (create-manager))"
  []
  (core/create-manager))

(defn reset-manager!
  "Reset the manager's store to empty. Useful for testing.

   Arguments:
   - manager: The DAG manager instance"
  [manager]
  (core/reset-manager! manager))

(defn get-all-dags
  "Get all DAGs from the manager's store.

   Arguments:
   - manager: The DAG manager instance

   Returns a sequence of all DAGs."
  [manager]
  (core/get-all-dags manager))

;------------------------------------------------------------------------------ Layer 2
;; DAG CRUD operations

(defn create-dag
  "Create a new empty DAG.

   Arguments:
   - manager: The DAG manager instance
   - dag-name: Name for the DAG
   - description: Optional description

   Returns the created DAG.

   Example:
     (create-dag mgr \"kiddom-infra\" \"Kiddom infrastructure repos\")"
  ([manager dag-name] (core/create-dag manager dag-name nil))
  ([manager dag-name description] (core/create-dag manager dag-name description)))

(defn get-dag
  "Retrieve a DAG by ID.

   Arguments:
   - manager: The DAG manager instance
   - dag-id: UUID of the DAG

   Returns the DAG or nil if not found."
  [manager dag-id]
  (core/get-dag manager dag-id))

(defn add-repo
  "Add a repository node to the DAG.

   Arguments:
   - manager: The DAG manager instance
   - dag-id: UUID of the DAG
   - repo-config: Map with repo fields (:repo/url, :repo/name, :repo/type required)

   Returns the updated DAG.

   Throws if:
   - DAG not found
   - Repo with same name already exists

   Example:
     (add-repo mgr dag-id
               {:repo/url \"https://github.com/acme/terraform-modules\"
                :repo/name \"terraform-modules\"
                :repo/type :terraform-module})"
  [manager dag-id repo-config]
  (core/add-repo manager dag-id repo-config))

(defn remove-repo
  "Remove a repository from the DAG.

   Arguments:
   - manager: The DAG manager instance
   - dag-id: UUID of the DAG
   - repo-name: Name of the repo to remove

   Returns the updated DAG.
   Also removes all edges referencing this repo.

   Throws if DAG not found."
  [manager dag-id repo-name]
  (core/remove-repo manager dag-id repo-name))

(defn add-edge
  "Add a dependency edge between repos.

   Arguments:
   - manager: The DAG manager instance
   - dag-id: UUID of the DAG
   - from-repo: Name of the upstream repo (dependency)
   - to-repo: Name of the downstream repo (dependent)
   - constraint: Edge constraint type (see edge-constraints)
   - merge-ordering: How PRs should merge (see merge-orderings)

   Returns the updated DAG.

   Throws if:
   - DAG not found
   - Either repo not in DAG
   - Edge would create a cycle
   - Edge already exists
   - Self-loop attempted

   Example:
     (add-edge mgr dag-id
               \"terraform-modules\" \"terraform-live\"
               :module-before-live :sequential)"
  [manager dag-id from-repo to-repo constraint merge-ordering]
  (core/add-edge manager dag-id from-repo to-repo constraint merge-ordering))

(defn remove-edge
  "Remove a dependency edge.

   Arguments:
   - manager: The DAG manager instance
   - dag-id: UUID of the DAG
   - from-repo: Name of the upstream repo
   - to-repo: Name of the downstream repo

   Returns the updated DAG.

   Throws if DAG not found."
  [manager dag-id from-repo to-repo]
  (core/remove-edge manager dag-id from-repo to-repo))

;------------------------------------------------------------------------------ Layer 3
;; Query operations

(defn compute-topo-order
  "Compute topological sort of repos in the DAG.

   Arguments:
   - manager: The DAG manager instance
   - dag-id: UUID of the DAG

   Returns:
   - On success: {:success true :order [repo-names...]}
   - On failure: {:success false :error :cycle-detected :cycle-nodes #{...}}

   Example:
     (compute-topo-order mgr dag-id)
     ;; => {:success true :order [\"terraform-modules\" \"terraform-live\" \"k8s\"]}"
  [manager dag-id]
  (core/compute-topo-order manager dag-id))

(defn affected-repos
  "Given a changed repo, return all downstream repos that may be affected.

   Arguments:
   - manager: The DAG manager instance
   - dag-id: UUID of the DAG
   - changed-repo: Name of the repo that changed

   Returns a set of repo names that are downstream (depend on) the changed repo.

   Example:
     (affected-repos mgr dag-id \"terraform-modules\")
     ;; => #{\"terraform-live\" \"k8s-manifests\"}"
  [manager dag-id changed-repo]
  (core/affected-repos manager dag-id changed-repo))

(defn upstream-repos
  "Return all repos that the given repo depends on.

   Arguments:
   - manager: The DAG manager instance
   - dag-id: UUID of the DAG
   - repo-name: Name of the repo to query

   Returns a set of repo names that are upstream (dependencies of) the given repo.

   Example:
     (upstream-repos mgr dag-id \"k8s-manifests\")
     ;; => #{\"terraform-modules\" \"terraform-live\"}"
  [manager dag-id repo-name]
  (core/upstream-repos manager dag-id repo-name))

(defn merge-order
  "Given a set of repos with PRs, compute valid merge order.

   Arguments:
   - manager: The DAG manager instance
   - dag-id: UUID of the DAG
   - pr-set: Set of repo names that have PRs to merge

   Returns:
   - On success: {:success true :order [repo-names...]}
   - On failure: {:success false :error :cycle-detected :cycle-nodes #{...}}

   Only considers dependencies between repos in the pr-set.

   Example:
     (merge-order mgr dag-id #{\"terraform-modules\" \"terraform-live\"})
     ;; => {:success true :order [\"terraform-modules\" \"terraform-live\"]}"
  [manager dag-id pr-set]
  (core/merge-order manager dag-id pr-set))

;------------------------------------------------------------------------------ Layer 4
;; Validation

(defn validate-dag
  "Check DAG for structural validity.

   Arguments:
   - manager: The DAG manager instance
   - dag-id: UUID of the DAG

   Returns:
   {:valid? boolean
    :errors [{:type keyword :message string :data any}...]}

   Checks for:
   - Cycles
   - Duplicate repo names
   - Edges referencing missing repos
   - Self-loops

   Example:
     (validate-dag mgr dag-id)
     ;; => {:valid? true :errors []}"
  [manager dag-id]
  (core/validate-dag manager dag-id))

;------------------------------------------------------------------------------ Layer 5
;; Pure functions (no manager required)

(defn topo-sort
  "Perform topological sort on a DAG directly.

   Arguments:
   - dag: A RepoDag map

   Returns:
   - On success: {:success true :order [repo-names...]}
   - On failure: {:success false :error :cycle-detected :cycle-nodes #{...}}

   This is a pure function that operates on the DAG data directly."
  [dag]
  (core/topo-sort dag))

(defn find-cycle-nodes
  "Find nodes involved in a cycle.

   Arguments:
   - dag: A RepoDag map

   Returns a set of repo names in the cycle, or nil if no cycle."
  [dag]
  (core/find-cycle-nodes dag))

(defn compute-layers
  "Group repos by their layer.

   Arguments:
   - dag: A RepoDag map

   Returns a map of layer keyword to vector of repo names.

   Example:
     (compute-layers dag)
     ;; => {:foundations [\"terraform-modules\"]
     ;;     :infrastructure [\"terraform-live\"]
     ;;     :platform [\"k8s-manifests\"]}"
  [dag]
  (core/compute-layers dag))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; --- Manager Lifecycle ---
  (def mgr (create-manager))
  (reset-manager! mgr)

  ;; --- DAG Creation ---
  (def dag (create-dag mgr "kiddom-infra" "Kiddom infrastructure repos"))

  ;; --- Add Repos ---
  (add-repo mgr (:dag/id dag)
            {:repo/url "https://github.com/kiddom/terraform-modules"
             :repo/name "terraform-modules"
             :repo/type :terraform-module})

  (add-repo mgr (:dag/id dag)
            {:repo/url "https://github.com/kiddom/terraform"
             :repo/name "terraform-live"
             :repo/type :terraform-live})

  (add-repo mgr (:dag/id dag)
            {:repo/url "https://github.com/kiddom/k8s-manifests"
             :repo/name "k8s-manifests"
             :repo/type :kubernetes})

  ;; --- Add Edges ---
  (add-edge mgr (:dag/id dag)
            "terraform-modules" "terraform-live"
            :module-before-live :sequential)

  (add-edge mgr (:dag/id dag)
            "terraform-live" "k8s-manifests"
            :infra-before-k8s :sequential)

  ;; --- Queries ---
  (compute-topo-order mgr (:dag/id dag))
  ;; => {:success true, :order ["terraform-modules" "terraform-live" "k8s-manifests"]}

  (affected-repos mgr (:dag/id dag) "terraform-modules")
  ;; => #{"terraform-live" "k8s-manifests"}

  (upstream-repos mgr (:dag/id dag) "k8s-manifests")
  ;; => #{"terraform-modules" "terraform-live"}

  (merge-order mgr (:dag/id dag) #{"terraform-live" "k8s-manifests"})
  ;; => {:success true, :order ["terraform-live" "k8s-manifests"]}

  ;; --- Validation ---
  (validate-dag mgr (:dag/id dag))
  ;; => {:valid? true, :errors []}

  ;; --- Pure Functions ---
  (def current-dag (get-dag mgr (:dag/id dag)))
  (topo-sort current-dag)
  (compute-layers current-dag)

  :leave-this-here)
