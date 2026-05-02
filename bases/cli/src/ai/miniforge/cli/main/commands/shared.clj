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

(ns ai.miniforge.cli.main.commands.shared
  "Shared utilities for CLI command implementations.

   Extracted to avoid duplication across artifact, etl, evidence,
   fleet, policy, and workflow command namespaces."
  (:require
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.main.display :as display]
   [ai.miniforge.cli.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0
;; Constants + helpers with no in-namespace dependencies.

(def max-artifacts-display
  "Maximum number of artifact files to display in a listing."
  50)

(def bytes-per-kb
  "Bytes in a kilobyte."
  1024)

(def bytes-per-mb
  "Bytes in a megabyte."
  1048576)

(def max-prs-per-repo
  "Maximum PRs to fetch per repository in fleet commands."
  20)

(def pr-risk-lines-high
  "Line-change threshold above which a PR is considered high risk."
  500)

(def pr-risk-lines-medium
  "Line-change threshold above which a PR is considered medium risk."
  100)

(def pr-risk-files-high
  "Changed-files threshold above which a PR is considered high risk."
  20)

(def pr-risk-files-medium
  "Changed-files threshold above which a PR is considered medium risk."
  5)

(defn try-resolve
  "Attempt to require-resolve a fully-qualified function symbol.
   Returns the resolved var (a function), or nil when the namespace
   or var cannot be loaded.  Does NOT call the function."
  [fn-sym]
  (try
    (requiring-resolve fn-sym)
    (catch Exception _ nil)))

(defn exit!
  "Wrapper around System/exit that can be redef'd in tests."
  [code]
  (System/exit code))

;------------------------------------------------------------------------------ Layer 1
;; Composes Layer 0.

(defn try-resolve-fn
  "Require-resolve `fn-sym` and immediately apply it to `args`.
   Returns nil when the namespace or var cannot be loaded."
  [fn-sym & args]
  (when-let [f (try-resolve fn-sym)]
    (try
      (apply f args)
      (catch Exception _ nil))))

(defn usage-error!
  "Print a usage error with the given message key and command string, then exit 1."
  [message-key command-suffix]
  (display/print-error
   (messages/t message-key {:command (app-config/command-string command-suffix)}))
  (exit! 1))
