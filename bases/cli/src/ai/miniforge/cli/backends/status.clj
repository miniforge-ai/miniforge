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
(ns ai.miniforge.cli.backends.status
  "Backend status checking, info assembly, and validation for Miniforge CLI.

   Split out of `ai.miniforge.cli.backends` (rule 210: the combined
   namespace measured 7 real layers, max 3) — resource-backed config
   lives in sibling `ai.miniforge.cli.backends.config`; display
   formatting and the top-level listing/printing entry points stay in
   the parent namespace."
  (:require
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.backends.config :as backend-config]
   [ai.miniforge.cli.messages :as messages]
   [babashka.process :as process]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} availability-status
  "Build a standard backend availability status map."
  [available status message]
  {:available available
   :status status
   :message message})

;; Status checking
(defn ^{:stratum 0} check-command-available?
  "Check if a command is available on PATH."
  [cmd]
  (try
    (let [result (process/sh "which" cmd)]
      (zero? (:exit result)))
    (catch Exception _
      false)))

(defn ^{:stratum 0} get-current-backend
  "Get the currently configured backend from config or env var."
  [config]
  (or (:backend (:llm config))
      (when-let [env-backend (System/getenv "MINIFORGE_LLM_BACKEND")]
        (keyword env-backend))
      (get backend-config/backend-defaults :current :codex)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} check-backend-status
  "Check the status of a backend.

   Returns map with:
   - :available - boolean
   - :status - :available, :not-installed
   - :message - human-readable status message"
  [backend-id]
  (let [spec (get backend-config/backend-specs backend-id)
        {:keys [check-type command]} spec]
    (case check-type
      :builtin
      (availability-status true :available (messages/t :backends/status-builtin))

      :cli
      (if (check-command-available? command)
        (availability-status true :available
                             (messages/t :backends/status-cli-found {:command command}))
        (availability-status false :not-installed
                             (messages/t :backends/status-cli-missing {:command command})))

      ;; Unknown check type
      (availability-status false :unknown (messages/t :backends/status-unknown)))))

;------------------------------------------------------------------------------ Layer 2

;; Backend information
(defn ^{:stratum 2} get-backend-info
  "Get detailed information about a backend."
  [backend-id]
  (let [spec (get backend-config/backend-specs backend-id)
        status (check-backend-status backend-id)]
    (merge spec status {:backend-id backend-id})))

(defn ^{:stratum 2} validate-backend
  "Validate that a backend can be used.
   Returns {:valid? true/false :message string}."
  [backend-id]
  (if-not (contains? backend-config/backend-specs backend-id)
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
