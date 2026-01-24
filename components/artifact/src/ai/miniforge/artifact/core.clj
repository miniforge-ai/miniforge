(ns ai.miniforge.artifact.core
  "Artifact storage and provenance tracking.
   Layer 0: Pure functions for artifact operations

   Note: The ArtifactStore protocol has been moved to:
   - ai.miniforge.artifact.interface.protocols.artifact-store

   Store implementations are in:
   - ai.miniforge.artifact.protocols.records.transit-store (Babashka compatible)
   - ai.miniforge.artifact.datalevin-store (JVM only)")

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

;; Protocol has been moved to ai.miniforge.artifact.interface.protocols.artifact-store
