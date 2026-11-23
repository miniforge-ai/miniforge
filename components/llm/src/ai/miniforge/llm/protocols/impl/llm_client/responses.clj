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
(ns ai.miniforge.llm.protocols.impl.llm-client.responses
  "The LLM response contract: the success and failure builders, the map
   from this brick's legacy anomaly categories to canonical anomaly types,
   the category for a provider HTTP status, and the canonical stop reason."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly])
  (:import
   [java.net HttpURLConnection]))

;------------------------------------------------------------------------------ Layer 0

;; LLM response builders
;;
;; All LLM responses follow the contract:
;;   Success: {:success true :content string :usage map}
;;   Failure: {:success false :error {:type string :message string} :anomaly anomaly-map}
;;
;; These builders ensure consistent construction across all backends
;; (CLI, HTTP/Ollama, streaming, non-streaming).
(defn ^{:stratum 0} llm-success
  "Build a successful LLM response."
  ([content]
   (llm-success content nil))
  ([content {:keys [usage exit-code]}]
   (let [usage (or usage {:input-tokens 0 :output-tokens 0})]
     (cond-> {:success true
              :content content
              :usage usage
              :tokens (+ (get usage :input-tokens 0) (get usage :output-tokens 0))}
       (some? exit-code) (assoc :exit-code exit-code)))))

(def ^{:stratum 0} ^:private category->type
  "W2 convergence map (subset). Maps every legacy `:anomalies/*` and
   `:anomalies.*/*` category this brick actually produces (per
   `(grep '(llm-error ' …)`) to the canonical `:anomaly/type` from the
   runbook (`docs/runbooks/anomaly-convergence.md`). Generic-standard
   categories map to a bare type and emit no subtype; domain categories
   map to a `(type, subtype)` pair where the subtype is the legacy
   category keyword verbatim.

   Only the categories actually emitted today are listed — an unmapped
   category throws `IllegalArgumentException` in `->canonical-anomaly`,
   forcing the table to grow when a new producer site is added (per
   the no-dead-code policy)."
  {:anomalies/fault              :fault
   :anomalies/unavailable        :unavailable
   :anomalies/unsupported        :unsupported
   :anomalies/timeout            :timeout
   :anomalies/incorrect          :invalid-input
   :anomalies/forbidden          :unauthorized
   :anomalies/not-found          :not-found
   :anomalies.agent/llm-error    :fault
   :anomalies.agent/rate-limited :unavailable})

(def ^{:stratum 0} ^:private generic-standard-categories
  "Subset of the cognitect-standard categories (per the runbook
   `Generic standard (no subtype)` table) that this brick emits — these
   seven map 1:1 to a type and carry no `:anomaly/subtype`. The runbook's
   full set is eight; only `:anomalies/conflict` is not produced by
   `llm-error` in this brick today."
  #{:anomalies/fault
    :anomalies/unavailable
    :anomalies/unsupported
    :anomalies/timeout
    :anomalies/incorrect
    :anomalies/forbidden
    :anomalies/not-found})

(defn ^{:stratum 0} normalize-finish-reason
  "Normalize a provider's finish reason to the canonical stop-reason
   strings used across all backends. The canonical strings are the
   Claude CLI's, which every consumer already compares against.

   - \"stop\" (Codex, OpenAI wire shape, Ollama), \"STOP\" (Gemini)
       → \"end_turn\"   (the model finished)
   - \"length\" (Codex, OpenAI wire shape, Ollama), \"MAX_TOKENS\" (Gemini)
       → \"max_tokens\" (the answer was cut at the output cap)
   - anything else → passed through unchanged (\"max_turns\",
     \"content_filter\", \"refusal\", \"SAFETY\", ...), so Anthropic's
     `stop_reason`, already canonical, is returned as given

   A missing or non-string reason yields nil."
  [finish-reason]
  (when (string? finish-reason)
    (case finish-reason
      ("stop" "STOP")         "end_turn"
      ("length" "MAX_TOKENS") "max_tokens"
      finish-reason)))

(def ^{:stratum 0} ^:private http-too-many-requests
  "HTTP 429. `java.net.HttpURLConnection` predates RFC 6585 and has no
   constant for it; the other status literals in this namespace come
   from that class."
  429)

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} ->canonical-anomaly
  "Build a canonical anomaly map from the brick's legacy category
   keyword + message + extra data. Generic-standard categories produce
   `(anomaly/anomaly type msg data)`; domain categories produce
   `(anomaly/sub-anomaly type category msg data)`.

   `category` MUST be in `category->type`; an unmapped category is a
   programmer error (the table must be updated when new categories
   are introduced)."
  [category message data]
  (let [a-type (or (category->type category)
                   (throw (IllegalArgumentException.
                           (str "Unmapped LLM anomaly category: "
                                (pr-str category)
                                ". Add it to `category->type` in "
                                "ai.miniforge.llm.protocols.impl.llm-client.responses"))))]
    (if (contains? generic-standard-categories category)
      (anomaly/anomaly a-type message data)
      (anomaly/sub-anomaly a-type category message data))))

(defn ^{:stratum 1} http-status->category
  "Map a non-OK provider HTTP status to the brick's legacy anomaly
   category, so a 403 classifies as forbidden and a 429 as rate-limited
   instead of everything collapsing into a generic api_error."
  [status]
  (cond
    (not (integer? status))                              :anomalies/unavailable
    (or (= status HttpURLConnection/HTTP_UNAUTHORIZED)
        (= status HttpURLConnection/HTTP_FORBIDDEN))  :anomalies/forbidden
    (= status HttpURLConnection/HTTP_NOT_FOUND)       :anomalies/not-found
    (= status http-too-many-requests)                 :anomalies.agent/rate-limited
    (< status HttpURLConnection/HTTP_INTERNAL_ERROR)  :anomalies/incorrect
    :else                                             :anomalies/unavailable))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} llm-error
  "Build a failed LLM response with anomaly.

   The `:anomaly` map is canonical (W2 convergence): `:anomaly/type` from
   the runbook, `:anomaly/subtype` set to the original category for
   domain categories (so failure-classifier / response/translate dispatch
   identically). The original `:operation` lives under `:anomaly/data`."
  ([category error-type message]
   (llm-error category error-type message nil))
  ([category error-type message
    {:keys [exit-code stderr stdout raw-stdout timeout failure-anomaly]}]
   (cond-> {:success false
            :error (cond-> {:type error-type :message message}
                     stderr  (assoc :stderr stderr)
                     stdout  (assoc :stdout stdout)
                     raw-stdout (assoc :raw-stdout raw-stdout)
                     timeout (assoc :timeout timeout))
            :anomaly (or failure-anomaly
                         (->canonical-anomaly
                          category message
                          {:operation :llm-complete}))}
     (some? exit-code) (assoc :exit-code exit-code))))
