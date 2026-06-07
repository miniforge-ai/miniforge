(ns ai.miniforge.connector.handles
  "Shared in-memory handle store for connector implementations.
   Each connector creates its own private store atom; these functions
   provide the shared CRUD operations over it.

   Layer 0: Store creation
   Layer 1: Store operations")

;;------------------------------------------------------------------------------ Layer 0
;; Store creation

(defn create
  []
  (atom {}))

;;------------------------------------------------------------------------------ Layer 1
;; Store operations

(defn get-handle
  [store handle]
  (get @store handle))

(defn store-handle!
  [store handle state]
  (swap! store assoc handle state))

(defn remove-handle!
  [store handle]
  (swap! store dissoc handle))

(defn touch-handle!
  [store handle]
  (swap! store assoc-in [handle :last-request-at] (System/currentTimeMillis)))
