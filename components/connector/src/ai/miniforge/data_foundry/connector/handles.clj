(ns ai.miniforge.data-foundry.connector.handles
  "Shared in-memory handle store for connector implementations.
   Each connector creates its own private store atom; these functions
   provide the shared CRUD operations over it.

   Layer 0: Store creation
   Layer 1: Store operations")

;;------------------------------------------------------------------------------ Layer 0
;; Store creation

(defn create
  "Create a new, empty handle store atom."
  []
  (atom {}))

;;------------------------------------------------------------------------------ Layer 1
;; Store operations

(defn get-handle
  "Look up handle state by handle ID. Returns nil if not found."
  [store handle]
  (get @store handle))

(defn store-handle!
  "Associate handle ID with state in the store."
  [store handle state]
  (swap! store assoc handle state))

(defn remove-handle!
  "Remove a handle and its state from the store."
  [store handle]
  (swap! store dissoc handle))

(defn touch-handle!
  "Record the current time as :last-request-at for a handle."
  [store handle]
  (swap! store assoc-in [handle :last-request-at] (System/currentTimeMillis)))
