(ns ai.miniforge.data-foundry.pipeline-pack.registry
  "In-memory pipeline pack registry.

   Atom-backed store for loaded packs. Supports register, list, query, and
   trust validation."
  (:require [ai.miniforge.data-foundry.pipeline-pack.messages :as msg]))

;------------------------------------------------------------------------------ Layer 0
;; Registry creation

(defn create-registry
  "Create a new empty pack registry."
  []
  (atom {}))

;------------------------------------------------------------------------------ Layer 1
;; Mutations

(defn register-pack!
  "Register a loaded pack in the registry. Returns the registry.
   Pack must have :pack/manifest with :pack/id."
  [registry pack]
  (let [pack-id (get-in pack [:pack/manifest :pack/id])]
    (swap! registry assoc pack-id pack)
    registry))

(defn unregister-pack!
  "Remove a pack from the registry by id."
  [registry pack-id]
  (swap! registry dissoc pack-id)
  registry)

;------------------------------------------------------------------------------ Layer 1
;; Queries

(defn get-pack
  "Get a pack by id. Returns nil if not found."
  [registry pack-id]
  (get @registry pack-id))

(defn list-packs
  "List all registered packs. Returns vector of pack maps."
  [registry]
  (vec (vals @registry)))

(defn list-pack-ids
  "List all registered pack ids."
  [registry]
  (vec (keys @registry)))

(defn pack-count
  "Return number of registered packs."
  [registry]
  (count @registry))

;------------------------------------------------------------------------------ Layer 2
;; Trust validation

(defn validate-pack-trust
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
