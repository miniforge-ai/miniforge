(ns ai.miniforge.repo-dag.core
  "Implementation of the repo-dag component.
   Layer 0: Pure functions for DAG operations
   Layer 1: Protocol definition
   Layer 2: In-memory implementation"
  (:require
   [clojure.set :as set]
   [clojure.string :as str]
   [malli.core :as m]
   [malli.error :as me]
   [ai.miniforge.repo-dag.schema :as schema]))

;------------------------------------------------------------------------------ Layer 0
;; Pure functions for DAG operations

(defn- validate-schema
  "Validate a value against a schema, returning the value or throwing."
  [schema-def value]
  (if (m/validate schema-def value)
    value
    (throw (ex-info "Schema validation failed"
                    {:schema schema-def
                     :value value
                     :errors (me/humanize (m/explain schema-def value))}))))

(defn make-repo-node
  "Create a repo node with layer inference if not specified."
  [{:keys [repo/url repo/name repo/org repo/type repo/layer repo/default-branch repo/watch-config]
    :or {default-branch "main"}}]
  (let [inferred-layer (or layer (schema/infer-layer type))
        node (cond-> {:repo/url url
                      :repo/name name
                      :repo/type type
                      :repo/layer inferred-layer
                      :repo/default-branch default-branch}
               org (assoc :repo/org org)
               watch-config (assoc :repo/watch-config watch-config))]
    (validate-schema schema/RepoNode node)))

(defn make-repo-edge
  "Create a repo edge with default merge ordering."
  [{:keys [edge/from edge/to edge/constraint edge/merge-ordering edge/validation]
    :or {merge-ordering :sequential}}]
  (let [edge (cond-> {:edge/from from
                      :edge/to to
                      :edge/constraint constraint
                      :edge/merge-ordering merge-ordering}
               validation (assoc :edge/validation validation))]
    (validate-schema schema/RepoEdge edge)))

(defn make-dag
  "Create a new empty DAG."
  [id dag-name description]
  (let [dag (cond-> {:dag/id id
                     :dag/name dag-name
                     :dag/repos []
                     :dag/edges []}
              description (assoc :dag/description description))]
    (validate-schema schema/RepoDag dag)))

(defn- find-repo-by-name
  "Find a repo node by name in the DAG."
  [dag repo-name]
  (some #(when (= repo-name (:repo/name %)) %) (:dag/repos dag)))

(defn- find-edge
  "Find an edge by from/to pair."
  [dag from-repo to-repo]
  (some #(when (and (= from-repo (:edge/from %))
                    (= to-repo (:edge/to %)))
           %)
        (:dag/edges dag)))

(defn- repo-names
  "Get set of all repo names in the DAG."
  [dag]
  (set (map :repo/name (:dag/repos dag))))

;------------------------------------------------------------------------------ Layer 0.5
;; Kahn's algorithm for topological sort

(defn topo-sort
  "Perform topological sort using Kahn's algorithm.
   Returns {:success true :order [...]} or {:success false :error :cycle-detected :cycle-nodes #{...}}"
  [dag]
  (let [nodes (repo-names dag)
        edges (:dag/edges dag)
        ;; Build in-degree map: count of incoming edges per node
        in-degree (reduce (fn [acc edge]
                            (update acc (:edge/to edge) (fnil inc 0)))
                          (zipmap nodes (repeat 0))
                          edges)
        ;; Build adjacency list: map from node to list of nodes it points to
        adj (reduce (fn [acc edge]
                      (update acc (:edge/from edge) (fnil conj []) (:edge/to edge)))
                    {}
                    edges)
        ;; Find initial set of nodes with no incoming edges
        initial-queue (filterv #(zero? (get in-degree %)) nodes)]
    ;; Kahn's algorithm loop
    (loop [queue (into clojure.lang.PersistentQueue/EMPTY initial-queue)
           in-deg in-degree
           result []]
      (if (empty? queue)
        ;; Check if all nodes were processed
        (if (= (count result) (count nodes))
          {:success true :order result}
          ;; Cycle detected - find nodes not in result
          (let [remaining (set/difference nodes (set result))]
            {:success false
             :error :cycle-detected
             :cycle-nodes remaining}))
        ;; Process next node
        (let [node (peek queue)
              neighbors (get adj node [])
              ;; Update in-degrees for neighbors
              new-in-deg (reduce #(update %1 %2 dec) in-deg neighbors)
              ;; Find newly ready neighbors (in-degree becomes 0)
              ready-neighbors (filterv #(zero? (get new-in-deg %)) neighbors)]
          (recur (into (pop queue) ready-neighbors)
                 new-in-deg
                 (conj result node)))))))

(defn find-cycle-nodes
  "Find nodes involved in a cycle. Returns nil if no cycle."
  [dag]
  (let [result (topo-sort dag)]
    (when-not (:success result)
      (:cycle-nodes result))))

;------------------------------------------------------------------------------ Layer 0.6
;; Graph traversal functions

(defn- build-adjacency
  "Build adjacency list from DAG edges."
  [dag]
  (reduce (fn [acc edge]
            (update acc (:edge/from edge) (fnil conj #{}) (:edge/to edge)))
          {}
          (:dag/edges dag)))

(defn- build-reverse-adjacency
  "Build reverse adjacency list (for finding upstream repos)."
  [dag]
  (reduce (fn [acc edge]
            (update acc (:edge/to edge) (fnil conj #{}) (:edge/from edge)))
          {}
          (:dag/edges dag)))

(defn downstream-repos
  "Get all repos that depend on the given repo (transitive closure)."
  [dag repo-name]
  (let [adj (build-adjacency dag)]
    (loop [to-visit #{repo-name}
           visited #{}]
      (if (empty? to-visit)
        (disj visited repo-name) ; Don't include the starting repo
        (let [current (first to-visit)
              neighbors (get adj current #{})]
          (recur (into (disj to-visit current)
                       (set/difference neighbors visited))
                 (conj visited current)))))))

(defn upstream-repos-impl
  "Get all repos that this repo depends on (transitive closure)."
  [dag repo-name]
  (let [rev-adj (build-reverse-adjacency dag)]
    (loop [to-visit #{repo-name}
           visited #{}]
      (if (empty? to-visit)
        (disj visited repo-name) ; Don't include the starting repo
        (let [current (first to-visit)
              neighbors (get rev-adj current #{})]
          (recur (into (disj to-visit current)
                       (set/difference neighbors visited))
                 (conj visited current)))))))

(defn compute-merge-order
  "Given a set of repo names, compute valid merge order based on DAG topology.
   Only considers repos in the pr-set."
  [dag pr-set]
  (let [pr-names (set pr-set)
        ;; Filter edges to only those between repos in pr-set
        relevant-edges (filterv (fn [edge]
                                  (and (contains? pr-names (:edge/from edge))
                                       (contains? pr-names (:edge/to edge))))
                                (:dag/edges dag))
        ;; Create sub-DAG
        sub-dag (assoc dag
                       :dag/repos (filterv #(contains? pr-names (:repo/name %))
                                           (:dag/repos dag))
                       :dag/edges relevant-edges)]
    (topo-sort sub-dag)))

(defn compute-layers
  "Group repos by their layer."
  [dag]
  (reduce (fn [acc repo]
            (let [rname (:repo/name repo)
                  rlayer (:repo/layer repo)]
              (update acc rlayer (fnil conj []) rname)))
          {}
          (:dag/repos dag)))

;------------------------------------------------------------------------------ Layer 0.7
;; Validation functions

(defn validate-dag-impl
  "Validate DAG structure. Returns {:valid? bool :errors [...]}"
  [dag]
  (let [repo-name-set (repo-names dag)
        errors (atom [])]
    ;; Check for duplicate repo names
    (let [names (map :repo/name (:dag/repos dag))
          freqs (frequencies names)
          dups (filterv #(> (val %) 1) freqs)]
      (doseq [[dup-name dup-count] dups]
        (swap! errors conj {:type :duplicate-repo
                            :message (str "Duplicate repo name: " dup-name " (appears " dup-count " times)")
                            :data {:repo-name dup-name :count dup-count}})))

    ;; Check for edges referencing missing repos
    (doseq [edge (:dag/edges dag)]
      (let [from-repo (:edge/from edge)
            to-repo (:edge/to edge)]
        (when-not (contains? repo-name-set from-repo)
          (swap! errors conj {:type :missing-repo
                              :message (str "Edge references missing repo: " from-repo)
                              :data {:edge-from from-repo :edge-to to-repo}}))
        (when-not (contains? repo-name-set to-repo)
          (swap! errors conj {:type :missing-repo
                              :message (str "Edge references missing repo: " to-repo)
                              :data {:edge-from from-repo :edge-to to-repo}}))))

    ;; Check for self-loops
    (doseq [edge (:dag/edges dag)]
      (let [from-repo (:edge/from edge)
            to-repo (:edge/to edge)]
        (when (= from-repo to-repo)
          (swap! errors conj {:type :self-loop
                              :message (str "Self-loop detected: " from-repo " -> " to-repo)
                              :data {:repo from-repo}}))))

    ;; Check for cycles
    (let [result (topo-sort dag)]
      (when-not (:success result)
        (swap! errors conj {:type :cycle
                            :message (str "Cycle detected involving repos: "
                                          (str/join ", " (:cycle-nodes result)))
                            :data {:cycle-nodes (:cycle-nodes result)}})))

    {:valid? (empty? @errors)
     :errors @errors}))

;------------------------------------------------------------------------------ Layer 1
;; Protocol definition

(defprotocol RepoDagManager
  "Protocol for managing repository dependency graphs."

  ;; CRUD operations
  (create-dag [this dag-name description]
    "Create a new empty DAG. Returns the created DAG.")

  (add-repo [this dag-id repo-config]
    "Add a repository node to the DAG. Returns updated DAG.")

  (remove-repo [this dag-id repo-name]
    "Remove a repository from the DAG (and its edges). Returns updated DAG.")

  (add-edge [this dag-id from-repo to-repo constraint merge-ordering]
    "Add a dependency edge between repos. Returns updated DAG or error if invalid.")

  (remove-edge [this dag-id from-repo to-repo]
    "Remove a dependency edge. Returns updated DAG.")

  ;; Queries
  (get-dag [this dag-id]
    "Retrieve a DAG by ID. Returns nil if not found.")

  (compute-topo-order [this dag-id]
    "Compute topological sort of repos. Returns {:success true :order [...]} or error.")

  (affected-repos [this dag-id changed-repo]
    "Given a changed repo, return all downstream repos that may be affected.")

  (upstream-repos [this dag-id repo-name]
    "Return all repos that this repo depends on.")

  (merge-order [this dag-id pr-set]
    "Given a set of PRs across repos, return valid merge order.")

  ;; Validation
  (validate-dag [this dag-id]
    "Check for cycles, orphans, invalid references. Returns {:valid? bool :errors [...]}."))

;------------------------------------------------------------------------------ Layer 2
;; In-memory implementation

(defrecord InMemoryDagManager [store]
  RepoDagManager

  (create-dag [_this dag-name description]
    (let [id (random-uuid)
          dag (make-dag id dag-name description)]
      (swap! store assoc id dag)
      dag))

  (add-repo [_this dag-id repo-config]
    (if-let [dag (get @store dag-id)]
      (let [node (make-repo-node repo-config)
            rname (:repo/name node)]
        ;; Check for duplicate
        (when (find-repo-by-name dag rname)
          (throw (ex-info "Repo already exists"
                          {:dag-id dag-id :repo-name rname})))
        (let [updated (update dag :dag/repos conj node)]
          (swap! store assoc dag-id updated)
          updated))
      (throw (ex-info "DAG not found" {:dag-id dag-id}))))

  (remove-repo [_this dag-id repo-name]
    (if-let [dag (get @store dag-id)]
      (let [;; Remove the repo
            updated-repos (filterv #(not= repo-name (:repo/name %)) (:dag/repos dag))
            ;; Remove edges referencing this repo
            updated-edges (filterv #(and (not= repo-name (:edge/from %))
                                         (not= repo-name (:edge/to %)))
                                   (:dag/edges dag))
            updated (assoc dag
                           :dag/repos updated-repos
                           :dag/edges updated-edges)]
        (swap! store assoc dag-id updated)
        updated)
      (throw (ex-info "DAG not found" {:dag-id dag-id}))))

  (add-edge [_this dag-id from-repo to-repo constraint merge-ordering]
    (if-let [dag (get @store dag-id)]
      (let [repo-name-set (repo-names dag)]
        ;; Validate repos exist
        (when-not (contains? repo-name-set from-repo)
          (throw (ex-info "From repo not found in DAG"
                          {:dag-id dag-id :repo-name from-repo})))
        (when-not (contains? repo-name-set to-repo)
          (throw (ex-info "To repo not found in DAG"
                          {:dag-id dag-id :repo-name to-repo})))
        ;; Check for self-loop
        (when (= from-repo to-repo)
          (throw (ex-info "Self-loop not allowed"
                          {:dag-id dag-id :repo-name from-repo})))
        ;; Check for existing edge
        (when (find-edge dag from-repo to-repo)
          (throw (ex-info "Edge already exists"
                          {:dag-id dag-id :from from-repo :to to-repo})))
        ;; Create edge
        (let [edge (make-repo-edge {:edge/from from-repo
                                    :edge/to to-repo
                                    :edge/constraint constraint
                                    :edge/merge-ordering merge-ordering})
              updated (update dag :dag/edges conj edge)
              ;; Check for cycles
              result (topo-sort updated)]
          (if (:success result)
            (do
              (swap! store assoc dag-id updated)
              updated)
            (throw (ex-info "Adding edge would create cycle"
                            {:dag-id dag-id
                             :from from-repo
                             :to to-repo
                             :cycle-nodes (:cycle-nodes result)})))))
      (throw (ex-info "DAG not found" {:dag-id dag-id}))))

  (remove-edge [_this dag-id from-repo to-repo]
    (if-let [dag (get @store dag-id)]
      (let [updated-edges (filterv #(not (and (= from-repo (:edge/from %))
                                              (= to-repo (:edge/to %))))
                                   (:dag/edges dag))
            updated (assoc dag :dag/edges updated-edges)]
        (swap! store assoc dag-id updated)
        updated)
      (throw (ex-info "DAG not found" {:dag-id dag-id}))))

  (get-dag [_this dag-id]
    (get @store dag-id))

  (compute-topo-order [_this dag-id]
    (if-let [dag (get @store dag-id)]
      (topo-sort dag)
      (throw (ex-info "DAG not found" {:dag-id dag-id}))))

  (affected-repos [_this dag-id changed-repo]
    (if-let [dag (get @store dag-id)]
      (downstream-repos dag changed-repo)
      (throw (ex-info "DAG not found" {:dag-id dag-id}))))

  (upstream-repos [_this dag-id repo-name]
    (if-let [dag (get @store dag-id)]
      (upstream-repos-impl dag repo-name)
      (throw (ex-info "DAG not found" {:dag-id dag-id}))))

  (merge-order [_this dag-id pr-set]
    (if-let [dag (get @store dag-id)]
      (compute-merge-order dag pr-set)
      (throw (ex-info "DAG not found" {:dag-id dag-id}))))

  (validate-dag [_this dag-id]
    (if-let [dag (get @store dag-id)]
      (validate-dag-impl dag)
      (throw (ex-info "DAG not found" {:dag-id dag-id})))))

;------------------------------------------------------------------------------ Layer 3
;; Factory functions

(defn create-manager
  "Create a new in-memory DAG manager."
  []
  (->InMemoryDagManager (atom {})))

(defn get-all-dags
  "Get all DAGs from the manager's store."
  [manager]
  (vals @(:store manager)))

(defn reset-manager!
  "Reset the manager's store. Useful for testing."
  [manager]
  (reset! (:store manager) {}))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create a manager
  (def mgr (create-manager))

  ;; Create a DAG
  (def dag (create-dag mgr "kiddom-infra" "Kiddom infrastructure repos"))

  ;; Add repos
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

  ;; Add edges
  (add-edge mgr (:dag/id dag)
            "terraform-modules" "terraform-live"
            :module-before-live :sequential)

  (add-edge mgr (:dag/id dag)
            "terraform-live" "k8s-manifests"
            :infra-before-k8s :sequential)

  ;; Compute topo order
  (compute-topo-order mgr (:dag/id dag))
  ;; => {:success true, :order ["terraform-modules" "terraform-live" "k8s-manifests"]}

  ;; Get affected repos
  (affected-repos mgr (:dag/id dag) "terraform-modules")
  ;; => #{"terraform-live" "k8s-manifests"}

  ;; Get upstream repos
  (upstream-repos mgr (:dag/id dag) "k8s-manifests")
  ;; => #{"terraform-modules" "terraform-live"}

  ;; Validate
  (validate-dag mgr (:dag/id dag))
  ;; => {:valid? true, :errors []}

  ;; Try to create a cycle
  (try
    (add-edge mgr (:dag/id dag)
              "k8s-manifests" "terraform-modules"
              :library-before-consumer :sequential)
    (catch Exception e
      (ex-data e)))
  ;; => {:dag-id ..., :from "k8s-manifests", :to "terraform-modules", :cycle-nodes #{"terraform-modules" "terraform-live" "k8s-manifests"}}

  :leave-this-here)
