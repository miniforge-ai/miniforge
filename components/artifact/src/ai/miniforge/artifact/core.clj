(ns ai.miniforge.artifact.core
  "Artifact storage and provenance tracking.
   Layer 0: Pure functions for artifact operations
   Layer 1: ArtifactStore protocol and Datalevin implementation"
  (:require
   [datalevin.core :as d]
   [ai.miniforge.logging.interface :as log]))

;------------------------------------------------------------------------------ Layer 0
;; Pure functions

(defn build-artifact
  "Build an artifact map from components."
  [{:keys [id type version content origin parents metadata]
    :or {parents [] metadata {}}}]
  (cond-> {:artifact/id id
           :artifact/type type
           :artifact/version version
           :artifact/content content
           :artifact/parents parents
           :artifact/metadata metadata}
    origin (assoc :artifact/origin origin)))

(defn add-parent
  "Add a parent artifact ID to an artifact's parents list."
  [artifact parent-id]
  (update artifact :artifact/parents (fnil conj []) parent-id))

(defn add-child
  "Add a child artifact ID to an artifact's children list."
  [artifact child-id]
  (update artifact :artifact/children (fnil conj []) child-id))

;------------------------------------------------------------------------------ Layer 1
;; ArtifactStore protocol

(defprotocol ArtifactStore
  "Protocol for artifact persistence and retrieval."
  (save [this artifact]
    "Persist an artifact. Returns the artifact ID.")
  (load-artifact [this id]
    "Retrieve an artifact by ID. Returns nil if not found.")
  (query [this criteria]
    "Find artifacts matching criteria. Returns vector of artifacts.")
  (link [this parent-id child-id]
    "Establish provenance link between parent and child artifacts.
     Returns true on success.")
  (close [this]
    "Close the store and release resources."))

(def datalevin-schema
  "Datalevin schema for artifact storage."
  {:artifact/id       {:db/unique :db.unique/identity}
   :artifact/type     {}
   :artifact/version  {}
   :artifact/parents  {:db/cardinality :db.cardinality/many}
   :artifact/children {:db/cardinality :db.cardinality/many}})

(defrecord DatalevinStore [conn logger]
  ArtifactStore
  (save [_this artifact]
    (let [id (:artifact/id artifact)]
      (d/transact! conn [artifact])
      (when logger
        (log/debug logger :system :artifact/saved
                   {:data {:artifact-id id
                           :artifact-type (:artifact/type artifact)}}))
      id))

  (load-artifact [_this id]
    (let [db (d/db conn)
          result (d/entity db [:artifact/id id])]
      (when result
        (into {} result))))

  (query [_this criteria]
    (let [db (d/db conn)]
      (if (empty? criteria)
        ;; Return all artifacts if no criteria
        (let [all-ids (d/q '[:find ?e :where [?e :artifact/id]] db)]
          (mapv (fn [[eid]] (into {} (d/entity db eid))) all-ids))
        ;; Simple filter-based query (Phase 1 - good enough)
        (let [all-artifacts (d/q '[:find ?e :where [?e :artifact/id]] db)
              artifacts (mapv (fn [[eid]] (into {} (d/entity db eid))) all-artifacts)]
          (filter (fn [art]
                    (every? (fn [[k v]] (= (get art k) v)) criteria))
                  artifacts)))))

  (link [_this parent-id child-id]
    (try
      ;; Add child to parent's children list
      (d/transact! conn [[:db/add [:artifact/id parent-id] :artifact/children child-id]])
      ;; Add parent to child's parents list
      (d/transact! conn [[:db/add [:artifact/id child-id] :artifact/parents parent-id]])
      (when logger
        (log/debug logger :system :artifact/linked
                   {:data {:parent-id parent-id
                           :child-id child-id}}))
      true
      (catch Exception e
        (when logger
          (log/error logger :system :artifact/link-failed
                     {:message (.getMessage e)
                      :data {:parent-id parent-id
                             :child-id child-id}}))
        false)))

  (close [_this]
    (d/close conn)))

(defn create-store
  "Create a new artifact store.

   Options:
   - :dir      - Directory for storage (nil for in-memory)
   - :logger   - Optional logger
   - :schema   - Optional custom Datalevin schema (defaults to datalevin-schema)

   Examples:
     (create-store)                          ; in-memory
     (create-store {:dir \"data/artifacts\"})  ; persistent
     (create-store {:logger my-logger})"
  ([] (create-store {}))
  ([{:keys [dir logger schema] :or {schema datalevin-schema}}]
   (let [conn (if dir
                (d/get-conn dir schema)
                (d/get-conn nil schema))]
     (when logger
       (log/info logger :system :artifact/store-created
                 {:data {:type (if dir :persistent :in-memory)
                         :dir dir}}))
     (->DatalevinStore conn logger))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create in-memory store
  (def store (create-store))

  ;; Create persistent store
  (def store (create-store {:dir "data/artifacts"}))

  ;; Save an artifact
  (def art-id (random-uuid))
  (save store {:artifact/id art-id
               :artifact/type :code
               :artifact/version "1.0.0"
               :artifact/content {:file "foo.clj" :code "(defn hello [] \"world\")"}
               :artifact/origin {:intent-id (random-uuid)
                                 :agent-id (random-uuid)
                                 :task-id (random-uuid)}
               :artifact/parents []
               :artifact/metadata {:language :clojure}})

  ;; Load an artifact
  (load store art-id)

  ;; Query artifacts
  (query store {:artifact/type :code})

  ;; Link artifacts
  (def parent-id (random-uuid))
  (def child-id (random-uuid))
  (save store {:artifact/id parent-id :artifact/type :spec :artifact/version "1.0.0" :artifact/content "spec content"})
  (save store {:artifact/id child-id :artifact/type :code :artifact/version "1.0.0" :artifact/content "code content"})
  (link store parent-id child-id)

  ;; Close store
  (close store)

  :leave-this-here)
