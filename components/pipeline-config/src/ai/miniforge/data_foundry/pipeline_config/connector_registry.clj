(ns ai.miniforge.data-foundry.pipeline-config.connector-registry
  "Atom-backed registry mapping connector type keywords to factory functions."
  (:require [ai.miniforge.data-foundry.pipeline-config.messages :as msg])
  (:import [java.util UUID]))

(defn create-connector-registry
  "Create an empty connector registry."
  []
  (atom {}))

(defn register-connector!
  "Register a connector type with its factory function and metadata.
   factory-fn: zero-arg function returning a connector instance.
   metadata: connector metadata map (name, capabilities, etc.)."
  [registry type-kw factory-fn metadata]
  (swap! registry assoc type-kw {:factory-fn factory-fn :metadata metadata})
  nil)

(defn- entry->metadata
  "Extract [type-kw metadata] from a registry entry."
  [[k v]]
  [k (:metadata v)])

(defn list-connectors
  "Return all registered connector types with metadata."
  [registry]
  (into {} (map entry->metadata) @registry))

(defn instantiate-connectors
  "Given a registry and a map of {symbolic-ref → connector-type-keyword},
   create connector instances with assigned UUIDs.
   Returns {:connector-refs {symbolic-ref → uuid} :connectors {uuid → instance}}."
  [registry connector-refs]
  (let [reg @registry]
    (reduce-kv
     (fn [acc ref-name type-kw]
       (if-let [entry (get reg type-kw)]
         (let [uuid (UUID/randomUUID)
               instance ((:factory-fn entry))]
           (-> acc
               (assoc-in [:connector-refs ref-name] uuid)
               (assoc-in [:connectors uuid] instance)))
         (throw (ex-info (msg/t :registry/connector-not-found {:type type-kw})
                         {:type type-kw :ref ref-name}))))
     {:connector-refs {} :connectors {}}
     connector-refs)))
