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

(ns ai.miniforge.llm.protocols.records.llm-client
  "Record that implements LLMClient protocol.

   CLIClient - LLM client using CLI backends (claude, cursor, etc.)"
  (:require
   [ai.miniforge.llm.interface.protocols.llm-client :as p]
   [ai.miniforge.llm.protocols.impl.llm-client :as impl]
   [ai.miniforge.response.interface :as response]))

(defrecord CLIClient [config logger exec-fn stream-exec-fn]
  p/LLMClient
  (complete* [this request]
    (impl/complete-impl this request))

  (complete-stream* [this request on-chunk]
    (impl/complete-stream-impl this request on-chunk))

  (get-config [this]
    (impl/get-config-impl this)))

;------------------------------------------------------------------------------ Layer 1
;; Factory functions

(defn create-client
  "Create a new LLM client using a CLI backend.

   Options:
   - :backend - Backend keyword (:codex, :claude, :cursor, :echo) - default :codex
   - :logger  - Optional logger for request/response logging
   - :exec-fn - Optional execution function override (for testing)

   Example:
     (create-client)  ; uses codex CLI
     (create-client {:backend :cursor})
     (create-client {:backend :claude :logger my-logger})"
  ([] (create-client {}))
  ([{:keys [backend logger exec-fn stream-exec-fn model] :or {backend :codex}}]
   (when-not (contains? impl/backends backend)
     (response/throw-anomaly! :anomalies/incorrect
                             (str "Unknown backend: " backend)
                             {:backend backend
                              :available (keys impl/backends)}))
   (->CLIClient (cond-> {:backend backend}
                  model (assoc :model model))
                logger
                ;; Normalize so 1-arity user-supplied exec-fns keep
                ;; working when the impl invokes 2-arity (the
                ;; :prompt-via :stdin path on the claude backend).
                (impl/normalize-exec-fn (or exec-fn impl/default-exec-fn))
                stream-exec-fn)))
