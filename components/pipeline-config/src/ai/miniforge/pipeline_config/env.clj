(ns ai.miniforge.pipeline-config.env
  "Environment-specific configuration loading and merging."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [ai.miniforge.pipeline-config.messages :as msg]
            [ai.miniforge.schema.interface :as schema])
  (:import [java.nio.file Files NoSuchFileException]))

;------------------------------------------------------------------------------ Layer 0

;; ---------------------------------------------------------------------------
;; Environment variable interpolation
;; ---------------------------------------------------------------------------
(defn- ^{:stratum 0} resolve-env-var
  "Replace ${VAR_NAME} placeholders in a string with System/getenv values.
   Unresolvable vars are left as-is."
  [s]
  (str/replace s #"\$\{([^}]+)\}"
               (fn [[match var-name]]
                 (or (System/getenv var-name) match))))

(defn- ^{:stratum 0} connector-entry->type
  "Extract [ref-name connector-type] from an env connector entry."
  [[ref-name cfg]]
  [ref-name (:connector/type cfg)])

(defn ^{:stratum 0} extract-stage-configs
  "Extract the stage config overrides map from env config."
  [env-config]
  (or (:env/stages env-config) {}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} resolve-env-vars
  "Walk a data structure, resolving ${VAR} placeholders in all string values."
  [config]
  (walk/postwalk
   (fn [v] (if (string? v) (resolve-env-var v) v))
   config))

(defn ^{:stratum 1} extract-connector-types
  "Extract a {symbolic-ref → connector-type} map from env config.
   E.g., {:conn/file-src :file, :conn/file-sink :file}."
  [env-config]
  (into {} (map connector-entry->type) (:env/connectors env-config)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} load-env-config
  "Load an environment config EDN file.
   Returns schema/success with :env-config key or schema/failure.

   Expected format:
     {:env/name \"development\"
      :env/connectors {:conn/file-src {:connector/type :file ...} ...}
      :env/stages {\"Stage Name\" {:key val} ...}}"
  [path]
  (try
    ;; Read without an exists? check first, so nothing can change between
    ;; the check and the read. NIO names the absent-file case on its own;
    ;; java.io reports a permission error as FileNotFoundException too,
    ;; which would mislabel an unreadable file as a missing one.
    (let [config (-> (Files/readString (.toPath (io/file path)))
                     edn/read-string
                     resolve-env-vars)]
      (schema/success :env-config config))
    (catch NoSuchFileException _
      (schema/failure :env-config (msg/t :env/file-not-found {:path path})))
    (catch Exception e
      (schema/exception-failure :env-config e))))
