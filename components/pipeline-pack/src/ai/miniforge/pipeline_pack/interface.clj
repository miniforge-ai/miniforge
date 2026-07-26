(ns ai.miniforge.pipeline-pack.interface
  "Public API for the pipeline-pack component.

   Provides pack loading, discovery, registry management, and trust validation."
  (:require
   [ai.miniforge.pipeline-pack.schema :as pack-schema]
   [ai.miniforge.pipeline-pack.loader :as loader]
   [ai.miniforge.pipeline-pack.registry :as registry]))

;------------------------------------------------------------------------------ Layer 0

;; -- Schema re-exports --
(def ^{:stratum 0} TrustLevel
  "Malli enum schema for a pack's trust level: [:enum :tainted :untrusted :trusted].
   Use to validate or coerce :pack/trust-level."
  pack-schema/TrustLevel)

(def ^{:stratum 0} AuthorityChannel
  "Malli enum schema for a pack's authority channel: [:enum :authority/data :authority/instruction].
   :authority/data = data-only; :authority/instruction = code-bearing (requires :trusted)."
  pack-schema/AuthorityChannel)

(def ^{:stratum 0} PipelinePackManifest
  "Malli :map schema for a pipeline pack manifest (pack.edn contents).
   Required keys: :pack/id, :pack/name, :pack/version, :pack/description,
   :pack/author, :pack/trust-level, :pack/authority, :pack/pipelines (vector),
   :pack/envs (vector), :pack/created-at, :pack/updated-at. Optional:
   :pack/metric-registry (string), :pack/connector-types (set of keywords),
   :pack/extends (vector of {:pack-id string})."
  pack-schema/PipelinePackManifest)

(def ^{:stratum 0} trust-levels
  "Vector of the legal trust-level keywords, in ascending trust order:
   [:tainted :untrusted :trusted]."
  pack-schema/trust-levels)

(def ^{:stratum 0} authority-channels
  "Vector of the legal authority-channel keywords:
   [:authority/data :authority/instruction]."
  pack-schema/authority-channels)

;; -- Validation --
(def ^{:stratum 0} valid-manifest?
  "Predicate. Returns true if the given value conforms to PipelinePackManifest,
   else false. Takes a manifest map."
  pack-schema/valid-manifest?)

(def ^{:stratum 0} validate-manifest
  "Validate a manifest map against PipelinePackManifest.
   Returns {:valid? true :errors nil} on success, or
   {:valid? false :errors <humanized-error-map>} on failure."
  pack-schema/validate-manifest)

;; -- Loading --
(defn ^{:stratum 0} load-pack
  "Load a pipeline pack from a directory path.
   Returns {:success? true :pack ...} or {:success? false :error ...}"
  [dir-path]
  (loader/load-pack-from-directory dir-path))

(defn ^{:stratum 0} discover-packs
  "Discover all pipeline packs in a directory.
   Returns vector of {:path string :pack-id string}."
  [packs-dir]
  (loader/discover-packs packs-dir))

(defn ^{:stratum 0} load-all-packs
  "Load all packs from a packs directory.
   Returns {:loaded [...] :failed [...]}."
  [packs-dir]
  (loader/load-all-packs packs-dir))

;; -- Registry --
(defn ^{:stratum 0} create-registry
  "Create a new empty pack registry (atom-backed)."
  []
  (registry/create-registry))

(defn ^{:stratum 0} register-pack!
  "Register a loaded pack in the registry."
  [reg pack]
  (registry/register-pack! reg pack))

(defn ^{:stratum 0} unregister-pack!
  "Remove a pack from the registry by id."
  [reg pack-id]
  (registry/unregister-pack! reg pack-id))

(defn ^{:stratum 0} get-pack
  "Get a pack by id from registry."
  [reg pack-id]
  (registry/get-pack reg pack-id))

(defn ^{:stratum 0} list-packs
  "List all registered packs."
  [reg]
  (registry/list-packs reg))

(defn ^{:stratum 0} list-pack-ids
  "List all registered pack ids."
  [reg]
  (registry/list-pack-ids reg))

(defn ^{:stratum 0} pack-count
  "Return number of registered packs."
  [reg]
  (registry/pack-count reg))

;; -- Trust --
(defn ^{:stratum 0} validate-pack-trust
  "Validate that a pack's trust/authority combination is legal."
  [pack]
  (registry/validate-pack-trust pack))
