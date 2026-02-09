(ns ai.miniforge.cli.backends
  "Backend discovery and management for Miniforge CLI.

   Provides functions to list, check status, and configure LLM backends."
  (:require
   [clojure.string :as str]
   [babashka.process :as process]))

;------------------------------------------------------------------------------ Layer 0
;; Backend specifications

(def backend-specs
  {:claude
   {:provider "Anthropic"
    :description "Claude via Anthropic CLI"
    :command "claude"
    :installation "npm install -g @anthropic-ai/claude-cli"
    :api-key-var "ANTHROPIC_API_KEY"
    :models ["claude-sonnet-4-20250514" "claude-opus-4-20250514" "claude-haiku-4-20250514"]
    :check-type :cli}

   :codex
   {:provider "OpenAI"
    :description "OpenAI Codex via API"
    :command nil
    :installation "Set OPENAI_API_KEY environment variable"
    :api-key-var "OPENAI_API_KEY"
    :models ["gpt-4-turbo" "gpt-4" "gpt-3.5-turbo"]
    :check-type :api-key
    :docs-url "https://platform.openai.com/api-keys"}

   :openai
   {:provider "OpenAI"
    :description "OpenAI GPT-4 via API"
    :command nil
    :installation "Set OPENAI_API_KEY environment variable"
    :api-key-var "OPENAI_API_KEY"
    :models ["gpt-4-turbo" "gpt-4" "gpt-3.5-turbo"]
    :check-type :api-key
    :docs-url "https://platform.openai.com/api-keys"}

   :cursor
   {:provider "Cursor"
    :description "Cursor AI via CLI"
    :command "cursor-cli"
    :installation "Install from https://cursor.sh"
    :api-key-var nil
    :models ["cursor-default"]
    :check-type :cli
    :docs-url "https://cursor.sh"}

   :ollama
   {:provider "Ollama"
    :description "Local models via Ollama"
    :command "ollama"
    :installation "brew install ollama"
    :api-key-var nil
    :models ["codellama" "llama2" "mistral"]
    :check-type :cli
    :docs-url "https://ollama.ai"}

   :echo
   {:provider "Test"
    :description "Echo backend for testing (no actual LLM)"
    :command "echo"
    :installation "Built-in"
    :api-key-var nil
    :models ["echo"]
    :check-type :builtin}})

;------------------------------------------------------------------------------ Layer 1
;; Status checking

(defn- check-command-available?
  "Check if a command is available on PATH."
  [cmd]
  (try
    (let [result (process/sh "which" cmd)]
      (zero? (:exit result)))
    (catch Exception _
      false)))

(defn- check-api-key-set?
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
      {:available true
       :status :available
       :message "Built-in (always available)"}

      :cli
      (if (check-command-available? command)
        {:available true
         :status :available
         :message (str command " CLI found")}
        {:available false
         :status :not-installed
         :message (str command " not found on PATH")})

      :api-key
      (if (check-api-key-set? api-key-var)
        {:available true
         :status :available
         :message (str api-key-var " is set")}
        {:available false
         :status :needs-key
         :message (str "Needs " api-key-var)})

      ;; Unknown check type
      {:available false
       :status :unknown
       :message "Unknown backend type"})))

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
      :claude))

;------------------------------------------------------------------------------ Layer 3
;; Display helpers

(defn- status-icon
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
        (println "  3. Or add to ~/.miniforge/config.edn:")
        (println (str "     {:llm {:backend " backend-id "}}"))
        (println (str "  4. Try again: miniforge config backend " (name backend-id))))

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
                  "\nRun 'miniforge config backends' to see available backends.")}
    (let [status (check-backend-status backend-id)]
      (if (:available status)
        {:valid? true
         :message (:message status)}
        {:valid? false
         :message (:message status)}))))
