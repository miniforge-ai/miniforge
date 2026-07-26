(ns ai.miniforge.pipeline-pack.registry
  "In-memory pipeline pack registry.

   Atom-backed store for loaded packs. Supports register, list, query, and
   trust validation."
  (:require [ai.miniforge.pipeline-pack.messages :as msg]))

;------------------------------------------------------------------------------ Layer 0

;; Registry creation
(defn ^{:stratum 0} create-registry
  []
  (atom {}))

;; Mutations
(defn ^{:stratum 0} register-pack!
  "Register a loaded pack in the registry. Returns the registry.
   Pack must have :pack/manifest with :pack/id."
  [registry pack]
  (let [pack-id (get-in pack [:pack/manifest :pack/id])]
    (swap! registry assoc pack-id pack)
    registry))

(defn ^{:stratum 0} unregister-pack!
  [registry pack-id]
  (swap! registry dissoc pack-id)
  registry)

;; Queries
(defn ^{:stratum 0} get-pack
  "Get a pack by id. Returns nil if not found."
  [registry pack-id]
  (get @registry pack-id))

(defn ^{:stratum 0} list-packs
  "List all registered packs. Returns vector of pack maps."
  [registry]
  (vec (vals @registry)))

(defn ^{:stratum 0} list-pack-ids
  [registry]
  (vec (keys @registry)))

(defn ^{:stratum 0} pack-count
  [registry]
  (count @registry))

;; Trust validation
(defn ^{:stratum 0} validate-pack-trust
  "Validate that a pack's trust level is compatible with its authority.
   Data-only packs (:authority/data) work at any trust level.
   Instruction packs (:authority/instruction) require :trusted."
  [pack]
  (let [manifest (:pack/manifest pack)
        trust (:pack/trust-level manifest)
        authority (:pack/authority manifest)]
    (if (and (= authority :authority/instruction)
             (not= trust :trusted))
      {:valid? false
       :error (msg/t :pack/trust-violation {:id (:pack/id manifest) :trust trust})}
      {:valid? true})))
