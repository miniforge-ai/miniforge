(ns ai.miniforge.cli.backends
  "Backend discovery and management for Miniforge CLI.

   Provides functions to list, check status, and configure LLM backends."
  (:require
   [clojure.string :as str]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.resource-config :as resource-config]
   [babashka.process :as process]))

;------------------------------------------------------------------------------ Layer 0
;; Backend specifications

(def ^:private backend-config-resource
  "Classpath resource path for backend metadata and defaults."
  "config/cli/backends.edn")

(def ^:private backend-config
  (resource-config/merged-resource-config
   backend-config-resource
   nil
   {:backend/defaults {:current :codex}
    :backend/specs {}}))

(def backend-specs
  (:backend/specs backend-config))

(def ^:private backend-defaults
  (:backend/defaults backend-config))

(defn- availability-status
  "Build a standard backend availability status map."
  [available status message]
  {:available available
   :status status
   :message message})

;------------------------------------------------------------------------------ Layer 1
;; Status checking

(defn check-command-available?
  "Check if a command is available on PATH."
  [cmd]
  (try
    (let [result (process/sh "which" cmd)]
      (zero? (:exit result)))
    (catch Exception _
      false)))

(defn check-api-key-set?
  "Check if an API key environment variable is set."
  [var-name]
  (and var-name
       (not (str/blank? (System/getenv var-name)))))

(defn check-backend-status
  "Check the status of a backend.

   Returns map with:
   - :available - boolean
   - :status - :available, :needs-key, :not-installed
   - :message - human-readable status message"
  [backend-id]
  (let [spec (get backend-specs backend-id)
        {:keys [check-type command api-key-var]} spec]
    (case check-type
      :builtin
      (availability-status true :available "Built-in (always available)")

      :cli
      (if (check-command-available? command)
        (availability-status true :available (str command " CLI found"))
        (availability-status false :not-installed (str command " not found on PATH")))

      :api-key
      (if (check-api-key-set? api-key-var)
        (availability-status true :available (str api-key-var " is set"))
        (availability-status false :needs-key (str "Needs " api-key-var)))

      ;; Unknown check type
      (availability-status false :unknown "Unknown backend type"))))

;------------------------------------------------------------------------------ Layer 2
;; Backend information

(defn get-backend-info
  "Get detailed information about a backend."
  [backend-id]
  (let [spec (get backend-specs backend-id)
        status (check-backend-status backend-id)]
    (merge spec status {:backend-id backend-id})))

(defn list-backends
  "List all available backends with their status.

   Returns sequence of backend info maps, sorted by availability."
  []
  (let [backends (map get-backend-info (keys backend-specs))]
    (sort-by (juxt (comp not :available) :provider) backends)))

(defn get-current-backend
  "Get the currently configured backend from config or env var."
  [config]
  (or (:backend (:llm config))
      (when-let [env-backend (System/getenv "MINIFORGE_LLM_BACKEND")]
        (keyword env-backend))
      (get backend-defaults :current :codex)))

;------------------------------------------------------------------------------ Layer 3
;; Display helpers

(defn status-icon
  "Get status icon for backend."
  [status]
  (case status
    :available "✅"
    :needs-key "⚠️ "
    :not-installed "❌"
    "❓"))

(defn format-backend-status
  "Format backend status for display."
  [backend-info current-backend]
  (let [{:keys [backend-id provider description status message models installation docs-url]} backend-info
        is-current? (= backend-id current-backend)
        icon (status-icon status)
        name-str (str (name backend-id)
                     (when is-current? " (current)"))
        provider-str (str "(" provider ")")]
    (str/join "\n"
              (remove nil?
                      [(str icon " " name-str " " provider-str)
                       (str "   " description)
                       (str "   Status: " message)
                       (when models
                         (str "   Models: " (str/join ", " models)))
                       (when (and (not (:available backend-info))
                                 installation)
                         (str "   Install: " installation))
                       (when (and (not (:available backend-info))
                                 docs-url)
                         (str "   Docs: " docs-url))]))))

(defn print-backends
  "Print all backends with their status."
  [config]
  (let [backends (list-backends)
        current (get-current-backend config)]
    (println)
    (println "Available LLM Backends:")
    (println)
    (doseq [backend backends]
      (println (format-backend-status backend current))
      (println))))

(defn print-backend-error
  "Print helpful error message when backend is not available."
  [backend-id]
  (let [info (get-backend-info backend-id)
        {:keys [status api-key-var installation docs-url]} info]
    (println)
    (println (str "❌ Error: " (name backend-id) " backend is not available"))
    (println)
    (case status
      :needs-key
      (do
        (println (str "The " (name backend-id) " backend requires " api-key-var))
        (println)
        (println "To use this backend:")
        (println (str "  1. Get an API key from " (or docs-url "the provider")))
        (println "  2. Set the environment variable:")
        (println (str "     export " api-key-var "='your-key-here'"))
        (println (str "  3. Or add to " (app-config/config-path) ":"))
        (println (str "     {:llm {:backend " backend-id "}}"))
        (println (str "  4. Try again: "
                      (app-config/command-string "config backend" (name backend-id)))))

      :not-installed
      (do
        (println (str "The " (name backend-id) " backend requires installation."))
        (println)
        (println "To install:")
        (println (str "  " installation))
        (when docs-url
          (println (str "  Docs: " docs-url))))

      ;; Default
      (println (str "Unknown issue with backend: " (name backend-id))))
    (println)))

(defn validate-backend
  "Validate that a backend can be used.
   Returns {:valid? true/false :message string}."
  [backend-id]
  (if-not (contains? backend-specs backend-id)
    {:valid? false
     :message (str "Unknown backend: " (name backend-id)
                   "\nRun '" (app-config/command-string "config backends")
                   "' to see available backends.")}
    (let [status (check-backend-status backend-id)]
      (if (:available status)
        {:valid? true
         :message (:message status)}
        {:valid? false
         :message (:message status)}))))
