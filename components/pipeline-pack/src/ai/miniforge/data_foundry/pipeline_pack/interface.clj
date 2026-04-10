(ns ai.miniforge.data-foundry.pipeline-pack.interface
  "Public API for the pipeline-pack component.

   Provides pack loading, discovery, registry management, and trust validation."
  (:require
   [ai.miniforge.data-foundry.pipeline-pack.schema :as pack-schema]
   [ai.miniforge.data-foundry.pipeline-pack.loader :as loader]
   [ai.miniforge.data-foundry.pipeline-pack.registry :as registry]))

;; -- Schema re-exports --
(def TrustLevel pack-schema/TrustLevel)
(def AuthorityChannel pack-schema/AuthorityChannel)
(def PipelinePackManifest pack-schema/PipelinePackManifest)
(def trust-levels pack-schema/trust-levels)
(def authority-channels pack-schema/authority-channels)

;; -- Validation --
(def valid-manifest? pack-schema/valid-manifest?)
(def validate-manifest pack-schema/validate-manifest)

;; -- Loading --
(defn load-pack
  "Load a pipeline pack from a directory path.
   Returns {:success? true :pack ...} or {:success? false :error ...}"
  [dir-path]
  (loader/load-pack-from-directory dir-path))

(defn discover-packs
  "Discover all pipeline packs in a directory.
   Returns vector of {:path string :pack-id string}."
  [packs-dir]
  (loader/discover-packs packs-dir))

(defn load-all-packs
  "Load all packs from a packs directory.
   Returns {:loaded [...] :failed [...]}."
  [packs-dir]
  (loader/load-all-packs packs-dir))

;; -- Registry --
(defn create-registry
  "Create a new empty pack registry (atom-backed)."
  []
  (registry/create-registry))

(defn register-pack!
  "Register a loaded pack in the registry."
  [reg pack]
  (registry/register-pack! reg pack))

(defn unregister-pack!
  "Remove a pack from the registry by id."
  [reg pack-id]
  (registry/unregister-pack! reg pack-id))

(defn get-pack
  "Get a pack by id from registry."
  [reg pack-id]
  (registry/get-pack reg pack-id))

(defn list-packs
  "List all registered packs."
  [reg]
  (registry/list-packs reg))

(defn list-pack-ids
  "List all registered pack ids."
  [reg]
  (registry/list-pack-ids reg))

(defn pack-count
  "Return number of registered packs."
  [reg]
  (registry/pack-count reg))

;; -- Trust --
(defn validate-pack-trust
  "Validate that a pack's trust/authority combination is legal."
  [pack]
  (registry/validate-pack-trust pack))
