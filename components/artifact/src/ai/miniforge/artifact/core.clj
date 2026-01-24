(ns ai.miniforge.artifact.core
  "Artifact storage and provenance tracking.
   Layer 0: Pure functions for artifact operations
   Layer 1: ArtifactStore protocol

   Note: Store implementations (Datalevin, Transit) are in separate
   namespaces to avoid pulling in JVM-only dependencies.")

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

;; Store implementations are in separate namespaces:
;; - ai.miniforge.artifact.datalevin-store (JVM only)
;; - ai.miniforge.artifact.transit-store (Babashka compatible)
;;
;; Use ai.miniforge.artifact.interface/create-store or create-transit-store

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; See ai.miniforge.artifact.interface for store creation examples

  :end)
