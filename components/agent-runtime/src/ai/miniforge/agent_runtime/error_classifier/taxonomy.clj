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

(ns ai.miniforge.agent-runtime.error-classifier.taxonomy
  "Canonical 10-class failure taxonomy for the error classifier.

   All values are pure data — no I/O, no side effects.

   Strata note: this namespace is stratum 0 in the error-classifier sub-system.
   It must not require any other error-classifier namespace.
   Consumers (patterns, core, schema) sit above it.")

;;------------------------------------------------------------------------------ Layer 0
;; Canonical failure class keyword constants

(def failure-class-agent-backend
  "Agent runtime internal bugs — Claude Code, Codex, or miniforge agent framework itself."
  :failure.class/agent-backend)

(def failure-class-task-code
  "User code or spec errors — compilation failures, test failures, linting, bad specs."
  :failure.class/task-code)

(def failure-class-external-service
  "External service failures — GitHub outages, network errors, provider 5xx."
  :failure.class/external-service)

(def failure-class-rate-limit
  "Provider rate limiting — HTTP 429, 529, overloaded responses, quota exhaustion."
  :failure.class/rate-limit)

(def failure-class-timeout
  "Operation timeouts or stream stalls — request deadlines, idle stream detection."
  :failure.class/timeout)

(def failure-class-auth
  "Authentication or authorization failures — bad credentials, missing API keys, forbidden."
  :failure.class/auth)

(def failure-class-resource-exhaustion
  "Token budget, memory, or disk exhaustion — context window full, OOM, disk full."
  :failure.class/resource-exhaustion)

(def failure-class-configuration
  "Setup, config, or environment errors — missing config, wrong paths, backend not running."
  :failure.class/configuration)

(def failure-class-concurrency
  "Race conditions, worktree conflicts, or interrupted operations."
  :failure.class/concurrency)

(def failure-class-unknown
  "Unclassifiable failure. Never nil — the classifier always returns a class."
  :failure.class/unknown)

;;------------------------------------------------------------------------------ Layer 1
;; Closed set, Malli schema, and legacy type mapping — all pure data

(def failure-classes
  "Complete set of all valid failure class keywords.
   Used for membership checks and schema generation."
  #{failure-class-agent-backend
    failure-class-task-code
    failure-class-external-service
    failure-class-rate-limit
    failure-class-timeout
    failure-class-auth
    failure-class-resource-exhaustion
    failure-class-configuration
    failure-class-concurrency
    failure-class-unknown})

(def FailureClass
  "Malli [:enum ...] schema for the canonical failure class.
   Validates that a value is one of the 10 defined members.

   Usage:
     (require '[malli.core :as m])
     (m/validate FailureClass :failure.class/rate-limit) ;; => true
     (m/validate FailureClass :other/thing)              ;; => false"
  [:enum
   failure-class-agent-backend
   failure-class-task-code
   failure-class-external-service
   failure-class-rate-limit
   failure-class-timeout
   failure-class-auth
   failure-class-resource-exhaustion
   failure-class-configuration
   failure-class-concurrency
   failure-class-unknown])

(def legacy-type->failure-class
  "Deterministic map from old 3-class :type keywords to canonical :failure.class/* equivalents.

   Old system used three classes:
     :agent-backend  — agent runtime internal bugs
     :task-code      — user code / spec errors
     :external       — any external service or provider failure

   The backend-setup patterns carried :type :external but represent configuration
   issues, so :backend-setup maps to :failure.class/configuration."
  {:agent-backend failure-class-agent-backend
   :task-code     failure-class-task-code
   :external      failure-class-external-service
   :backend-setup failure-class-configuration})

;;------------------------------------------------------------------------------ Layer 2
;; Resolution function

(defn resolve-failure-class
  "Resolve a canonical failure class from a pattern map and error message.

   Resolution order:
   1. `:failure/class` on the pattern — explicit, authoritative; only used when
      the value is a member of `failure-classes` (guards against stale EDN typos)
   2. `:rate-limit? true` on the pattern — fine-grained rate-limit class
   3. `:type` on the pattern — legacy type → canonical class via legacy-type->failure-class
   4. :failure.class/unknown — default; never returns nil

   Arguments:
     pattern-map    - Pattern map that matched the error (may be nil or empty)
     _error-message - Error message string (reserved for future content-based overrides)

   Returns: Keyword from failure-classes — never nil."
  [pattern-map _error-message]
  (let [explicit-class (:failure/class pattern-map)]
    (cond
      (contains? failure-classes explicit-class)
      explicit-class

      (:rate-limit? pattern-map)
      failure-class-rate-limit

      (contains? legacy-type->failure-class (:type pattern-map))
      (get legacy-type->failure-class (:type pattern-map))

      :else
      failure-class-unknown)))

;;------------------------------------------------------------------------------ Rich comment
(comment
  ;; Validate a class keyword
  (require '[malli.core :as m])
  (m/validate FailureClass :failure.class/rate-limit)   ;; => true
  (m/validate FailureClass :failure.class/unknown)      ;; => true
  (m/validate FailureClass :rate-limit)                 ;; => false (old unqualified style)

  ;; Resolve from a pattern map
  (resolve-failure-class {:failure/class :failure.class/auth} "auth error")
  ;; => :failure.class/auth

  (resolve-failure-class {:rate-limit? true :type :external} "429")
  ;; => :failure.class/rate-limit

  (resolve-failure-class {:type :external} "GitHub 503")
  ;; => :failure.class/external-service

  (resolve-failure-class {:type :backend-setup} "ollama not running")
  ;; => :failure.class/configuration

  (resolve-failure-class nil "unknown error")
  ;; => :failure.class/unknown

  (resolve-failure-class {} "")
  ;; => :failure.class/unknown

  ;; Invalid `:failure/class` (not in failure-classes) falls through
  (resolve-failure-class {:failure/class :some/garbage :type :external} "msg")
  ;; => :failure.class/external-service
  )
