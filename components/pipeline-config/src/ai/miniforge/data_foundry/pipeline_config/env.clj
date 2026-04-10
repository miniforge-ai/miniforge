(ns ai.miniforge.data-foundry.pipeline-config.env
  "Environment-specific configuration loading and merging."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [ai.miniforge.data-foundry.pipeline-config.messages :as msg]
            [ai.miniforge.schema.interface :as schema]))

;; ---------------------------------------------------------------------------
;; Environment variable interpolation
;; ---------------------------------------------------------------------------

(defn- resolve-env-var
  "Replace ${VAR_NAME} placeholders in a string with System/getenv values.
   Unresolvable vars are left as-is."
  [s]
  (str/replace s #"\$\{([^}]+)\}"
               (fn [[match var-name]]
                 (or (System/getenv var-name) match))))

(defn resolve-env-vars
  "Walk a data structure, resolving ${VAR} placeholders in all string values."
  [config]
  (walk/postwalk
   (fn [v] (if (string? v) (resolve-env-var v) v))
   config))

(defn load-env-config
  "Load an environment config EDN file.
   Returns schema/success with :env-config key or schema/failure.

   Expected format:
     {:env/name \"development\"
      :env/connectors {:conn/file-src {:connector/type :file ...} ...}
      :env/stages {\"Stage Name\" {:key val} ...}}"
  [path]
  (try
    (let [f (io/file path)]
      (if (.exists f)
        (let [config (-> (slurp f) edn/read-string resolve-env-vars)]
          (schema/success :env-config config))
        (schema/failure :env-config (msg/t :env/file-not-found {:path path}))))
    (catch Exception e
      (schema/exception-failure :env-config e))))

(defn- connector-entry->type
  "Extract [ref-name connector-type] from an env connector entry."
  [[ref-name cfg]]
  [ref-name (:connector/type cfg)])

(defn extract-connector-types
  "Extract a {symbolic-ref → connector-type} map from env config.
   E.g., {:conn/file-src :file, :conn/file-sink :file}."
  [env-config]
  (into {} (map connector-entry->type) (:env/connectors env-config)))

(defn extract-stage-configs
  "Extract the stage config overrides map from env config."
  [env-config]
  (or (:env/stages env-config) {}))
