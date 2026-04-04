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

(ns ai.miniforge.dag-executor.result
  "Result helpers for the DAG executor. Delegates to dag-primitives."
  (:require [ai.miniforge.dag-primitives.interface :as dp]))

;;------------------------------------------------------------------------------ Layer 0
;; Delegating wrappers — preserve the existing public surface

(defn ok        [data]              (dp/ok data))
(defn err
  ([code message]      (dp/err code message))
  ([code message data] (dp/err code message data)))
(defn ok?       [result]            (dp/ok? result))
(defn err?      [result]            (dp/err? result))
(defn unwrap    [result]            (dp/unwrap result))
(defn unwrap-or [result default]    (dp/unwrap-or result default))
(defn map-ok    [result f]          (dp/map-ok result f))
(defn map-err   [result f]          (dp/map-err result f))
(defn and-then  [result f]          (dp/and-then result f))
(defn or-else   [result f]          (dp/or-else result f))
(defn collect   [results]           (dp/collect results))

;;------------------------------------------------------------------------------ Layer 3
;; DAG-executor–specific error codes (not part of dag-primitives)

(def error-codes
  "Standard error codes for DAG execution."
  #{:invalid-state
    :invalid-transition
    :task-not-found
    :dependency-cycle
    :budget-exhausted
    :max-iterations
    :concurrent-modification
    :ci-failed
    :review-rejected
    :merge-conflict
    :merge-failed
    :pr-creation-failed
    :rebase-failed
    :timeout})
