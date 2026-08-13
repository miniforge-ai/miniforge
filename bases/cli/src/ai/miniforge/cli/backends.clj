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

   Provides functions to list, check status, and configure LLM backends.

   Resource-backed config lives in `ai.miniforge.cli.backends.config`;
   status checking, info assembly, and validation live in
   `ai.miniforge.cli.backends.status` (rule 210: the combined namespace
   measured 7 real layers, max 3) — this namespace keeps display
   formatting and the top-level listing/printing entry points."
  (:require
   [clojure.string :as str]
   [ai.miniforge.cli.backends.config :as backend-config]
   [ai.miniforge.cli.backends.status :as backend-status]
   [ai.miniforge.cli.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0

;; Display helpers
(defn ^{:stratum 0} status-icon
  "Get status icon for backend."
  [status]
  (case status
    :available "✅"
    :not-installed "❌"
    "❓"))

(defn ^{:stratum 0} list-backends
  "List all available backends with their status.

   Returns sequence of backend info maps, sorted by availability."
  []
  (let [backends (map backend-status/get-backend-info (keys backend-config/backend-specs))]
    (sort-by (juxt (comp not :available) :provider) backends)))

(defn ^{:stratum 0} print-backend-error
  "Print helpful error message when backend is not available."
  [backend-id]
  (let [info (backend-status/get-backend-info backend-id)
        {:keys [status installation docs-url]} info
        backend-name (name backend-id)]
    (println)
    (println (messages/t :backends/error-not-available {:backend backend-name}))
    (println)
    (case status
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

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} format-backend-status
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

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} print-backends
  "Print all backends with their status."
  [config]
  (let [backends (list-backends)
        current (backend-status/get-current-backend config)]
    (println)
    (println (messages/t :backends/list-header))
    (println)
    (doseq [backend backends]
      (println (format-backend-status backend current))
      (println))))
