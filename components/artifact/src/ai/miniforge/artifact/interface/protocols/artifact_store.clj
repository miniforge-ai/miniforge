(ns ai.miniforge.artifact.interface.protocols.artifact-store
  "Public protocol for artifact persistence and retrieval.

   This is an extensibility point - users can implement custom artifact stores
   to persist artifacts to different backends (databases, file systems, cloud storage, etc.).")

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
