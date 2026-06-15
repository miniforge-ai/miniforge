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

(ns ai.miniforge.agent.result-boundary
  "Shared result-boundary helpers for agent roles that can receive structured
   outcomes through multiple channels."
  (:require
   [ai.miniforge.llm.interface :as llm]
   [ai.miniforge.response.interface :as response]
   [clojure.string :as str]))

(defn normalize-llm-result
  "Fan in known result channels for an agent turn into one normalized map."
  [{:keys [role response worktree-artifacts artifact fallback-artifact
           parse-response derive-artifact content-fn]}]
  (let [extract-content (or content-fn llm/get-content)
        content (or (extract-content response) "")
        worktree-artifact (when role
                            (get worktree-artifacts role))
        artifact-with-fallback (when (and artifact
                                          fallback-artifact
                                          (map? artifact)
                                          (map? fallback-artifact)
                                          (contains? fallback-artifact :code/files)
                                          (not (contains? artifact :code/files)))
                                 (merge fallback-artifact artifact))
        artifact-source (cond
                          worktree-artifact :worktree-metadata
                          artifact-with-fallback :mcp-with-file-fallback
                          artifact :mcp
                          fallback-artifact :file-fallback
                          :else nil)
        structured-artifact (or worktree-artifact artifact-with-fallback
                                artifact fallback-artifact)
        parsed-content (when parse-response
                         (parse-response content))
        derived-artifact (when derive-artifact
                           (derive-artifact content))]
    {:response response
     :content content
     :response-success? (llm/success? response)
     :llm-error (llm/get-error response)
     :artifact-source artifact-source
     :structured-artifact structured-artifact
     :parsed-content parsed-content
     :derived-artifact derived-artifact
     :tokens (get response :tokens 0)
     :cost-usd (get response :cost-usd)
     :stop-reason (:stop-reason response)
     :num-turns (:num-turns response)
     :tools-called (get response :tools-called [])
     :usable? (boolean
               (or structured-artifact
                   parsed-content
                   derived-artifact
                   (llm/success? response)))}))

(defn authoritative-payload
  "Return the canonical payload chosen by the normalized boundary."
  [{:keys [structured-artifact parsed-content derived-artifact]}]
  (or structured-artifact parsed-content derived-artifact))

;------------------------------------------------------------------------------ LLM-timeout classification
;;
;; `llm-client/timeout-result` packs an adaptive-timeout reason into the
;; LLM-error's `:timeout` field. The `:type` keyword inside that field is
;; the canonical taxonomy: `:stream-idle`, `:stagnation`, `:hard-limit`,
;; `:network-drop` (PR-B of network-resilience).
;;
;; The reviewer's existing `timeout-only-review?` reads from the PARSED
;; review map, which is nil whenever the LLM call itself errored (no
;; response to parse). The 2026-06-05 dogfood (workflow `adhoc-944448986`)
;; failed at exactly that path — reviewer-LLM stream-idle'd at 360s, no
;; parsed review, the framework's parse-failed branch promoted the
;; timeout text into `:review/blocking-issues` and synthesized a false
;; `:rejected`. These helpers detect the same condition at the BOUNDARY
;; level (the normalized result), so callers can branch on infra timeouts
;; even when the parser produced nothing.

(defn llm-timeout-type
  "Return the timeout-type keyword from a normalized boundary, or nil if
   the response carries no timeout envelope.

   The LLM-client's `streaming-error-response` packs the timeout reason
   produced by `stream-idle-timeout` / `stagnation-timeout` / etc. into
   the error's `:timeout` field; this helper just pulls the canonical
   `:type` keyword out of it.

   Reads `(:llm-error normalized)` (the map produced by `llm/get-error`)
   for `[:timeout :type]`. Returns nil for non-error responses, for
   error responses without a `:timeout` envelope, and for normalized
   maps where `:llm-error` is missing entirely."
  [normalized]
  (get-in normalized [:llm-error :timeout :type]))

(defn stream-idle-error?
  "True when the normalized boundary carries an LLM-client adaptive
   timeout of `:type :stream-idle` — i.e. the provider connected but
   stopped producing stream output for the configured idle threshold.

   Distinct from `network-drop-error?` (TCP/TLS reachability lost) and
   from a real LLM rejection (parsed content with `:rejected` verdict).
   Callers should treat this as an INFRA failure — retry from the
   last persisted checkpoint, do not promote into the artifact's
   blocking-issues / repair-request channel."
  [normalized]
  (= :stream-idle (llm-timeout-type normalized)))

(defn network-drop-error?
  "True when the normalized boundary carries an LLM-client adaptive
   timeout of `:type :network-drop` — the connectivity-lost verdict
   emitted by PR-B's network-monitor after `failure-threshold`
   consecutive probe failures.

   Distinct from `stream-idle-error?` (provider connected, no tokens)
   and from a real LLM rejection. Callers should retry from the last
   persisted checkpoint once connectivity is restored."
  [normalized]
  (= :network-drop (llm-timeout-type normalized)))

(def ^:private adaptive-timeout-types
  "The adaptive-timeout `:type`s that mean the LLM call ended WITHOUT producing
   a usable verdict — the provider was reachable but the turn never completed.
   `:network-drop` is excluded: it is connectivity-loss with dedicated
   retry/resume handling (PR-C auto-resumer), not a no-verdict timeout."
  #{:stream-idle :stagnation :hard-limit})

(defn backend-timeout-error?
  "True when the normalized boundary carries ANY adaptive LLM timeout
   (`:stream-idle` / `:stagnation` / `:hard-limit`) — the call timed out before
   the model produced a verdict. Callers (e.g. the reviewer) must treat this as
   an INFRA failure (a backend-timeout verdict to retry / terminate), NOT as a
   parse failure that synthesizes a content `:rejected`. Broader than
   `stream-idle-error?`, which catches only one of the three types."
  [normalized]
  (boolean (adaptive-timeout-types (llm-timeout-type normalized))))

(defn error-response
  "Build a failure response that preserves the backend error shape plus
   common response metadata for post-mortem."
  ([normalized default-message]
   (error-response normalized default-message {}))
  ([{:keys [llm-error stop-reason num-turns tokens]} default-message extra]
   (let [error-msg (or (:message llm-error) default-message)
         data (cond-> (merge (or llm-error {}) (:data extra))
                stop-reason (assoc :stop-reason stop-reason)
                num-turns   (assoc :num-turns num-turns))]
     (response/error error-msg
                     (cond-> extra
                       tokens (assoc :tokens tokens)
                       (seq data) (assoc :data data))))))

(defn usable-content?
  "True when the boundary has any structured or parseable outcome."
  [normalized]
  (boolean (:usable? normalized)))

(defn parse-failed?
  "True when non-blank content produced no structured or parseable payload."
  [{:keys [content structured-artifact parsed-content derived-artifact]}]
  (and (not (str/blank? content))
       (nil? structured-artifact)
       (nil? parsed-content)
       (nil? derived-artifact)))
