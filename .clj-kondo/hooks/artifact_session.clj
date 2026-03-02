(ns hooks.artifact-session
  (:require [clj-kondo.hooks-api :as api]))

(defn with-artifact-session
  "Expands (with-artifact-session [session] body...) to (let [session nil] body...)
   so clj-kondo can resolve the binding."
  [{:keys [node]}]
  (let [children (rest (:children node))
        binding-vec (first children)
        session-sym (first (:children binding-vec))
        body (rest children)]
    {:node (api/list-node
            (list* (api/token-node 'let)
                   (api/vector-node [session-sym (api/token-node nil)])
                   body))}))
