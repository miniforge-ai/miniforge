;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns ai.miniforge.cli.backends
  "Backend discovery and management for Miniforge CLI.

   Provides functions to list, check status, and configure LLM backends."
  (:require
   [clojure.string :as str]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.messages :as messages]
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
      (availability-status true :available (messages/t :backends/status-builtin))

      :cli
      (if (check-command-available? command)
        (availability-status true :available
                             (messages/t :backends/status-cli-found {:command command}))
        (availability-status false :not-installed
                             (messages/t :backends/status-cli-missing {:command command})))

      :api-key
      (if (check-api-key-set? api-key-var)
        (availability-status true :available
                             (messages/t :backends/status-api-key-set {:api-key-var api-key-var}))
        (availability-status false :needs-key
                             (messages/t :backends/status-needs-key {:api-key-var api-key-var})))

      ;; Unknown check type
      (availability-status false :unknown (messages/t :backends/status-unknown)))))

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
                     (when is-current? (messages/t :backends/current-suffix)))]
    (str/join "\n"
              (remove nil?
                      [(messages/t :backends/name-line
                                   {:icon icon
                                    :name name-str
                                    :provider (messages/t :backends/provider-suffix
                                                          {:provider provider})})
                       (messages/t :backends/description-line {:description description})
                       (messages/t :backends/status-line {:message message})
                       (when models
                         (messages/t :backends/models-line
                                     {:models (str/join ", " models)}))
                       (when (and (not (:available backend-info))
                                 installation)
                         (messages/t :backends/install-line {:installation installation}))
                       (when (and (not (:available backend-info))
                                 docs-url)
                         (messages/t :backends/docs-line {:docs-url docs-url}))]))))

(defn print-backends
  "Print all backends with their status."
  [config]
  (let [backends (list-backends)
        current (get-current-backend config)]
    (println)
    (println (messages/t :backends/list-header))
    (println)
    (doseq [backend backends]
      (println (format-backend-status backend current))
      (println))))

(defn print-backend-error
  "Print helpful error message when backend is not available."
  [backend-id]
  (let [info (get-backend-info backend-id)
        {:keys [status api-key-var installation docs-url]} info
        backend-name (name backend-id)]
    (println)
    (println (messages/t :backends/error-not-available {:backend backend-name}))
    (println)
    (case status
      :needs-key
      (do
        (println (messages/t :backends/needs-key-intro
                             {:backend backend-name :api-key-var api-key-var}))
        (println)
        (println (messages/t :backends/needs-key-howto-header))
        (println (messages/t :backends/needs-key-step-1
                             {:docs-url (or docs-url
                                            (messages/t :backends/needs-key-provider-fallback))}))
        (println (messages/t :backends/needs-key-step-2))
        (println (messages/t :backends/needs-key-step-2-cmd {:api-key-var api-key-var}))
        (println (messages/t :backends/needs-key-step-3 {:config-path (app-config/config-path)}))
        (println (messages/t :backends/needs-key-step-3-cmd {:backend backend-id}))
        (println (messages/t :backends/needs-key-step-4
                             {:command (app-config/command-string "config backend"
                                                                  backend-name)})))

      :not-installed
      (do
        (println (messages/t :backends/not-installed-intro {:backend backend-name}))
        (println)
        (println (messages/t :backends/not-installed-howto-header))
        (println (messages/t :backends/not-installed-install-line {:installation installation}))
        (when docs-url
          (println (messages/t :backends/not-installed-docs-line {:docs-url docs-url}))))

      ;; Default
      (println (messages/t :backends/unknown-issue {:backend backend-name})))
    (println)))

(defn validate-backend
  "Validate that a backend can be used.
   Returns {:valid? true/false :message string}."
  [backend-id]
  (if-not (contains? backend-specs backend-id)
    {:valid? false
     :message (messages/t :backends/validate-unknown
                          {:backend (name backend-id)
                           :command (app-config/command-string "config backends")})}
    (let [status (check-backend-status backend-id)]
      (if (:available status)
        {:valid? true
         :message (:message status)}
        {:valid? false
         :message (:message status)}))))
