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
(ns ai.miniforge.agent.implementer.response
  "Implementer agent responses built from a normalized LLM turn: a code
   artifact, an already-implemented claim, and the rejection paths."
  (:require
   [ai.miniforge.agent.implementer.code :as impl-code]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.repo-index.interface :as messages]
   [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} build-already-implemented-response
  "Build the response map for an already-implemented task."
  [parsed tokens cost-usd]
  {:status :already-implemented
   :output {:code/id (random-uuid)
            :code/files []
            :code/summary (:summary parsed)
            :code/language nil
            :code/tests-needed? false
            :code/created-at (java.util.Date.)}
   :summary (:summary parsed)
   :metrics {:tokens tokens
             :cost-usd cost-usd
             :files-created 0
             :skipped-reason :already-implemented}})

(defn- ^{:stratum 0} repair-attempt?
  "Return true when the implementer is handling prior review or verify feedback."
  [input]
  (or (:task/review-feedback input)
      (:task/verify-failures input)))

(defn- ^{:stratum 0} unverified-already-implemented-response
  "Reject an :already-implemented claim when there is no artifact evidence on a repair attempt.
   The rejected turn's tokens and cost stay on the response; a nil cost is
   dropped by the response builder."
  [input tokens cost-usd]
  (response/error
   (messages/t :error/unverified-already-implemented)
   {:tokens tokens
    :metrics {:cost-usd cost-usd}
    :data {:code :implementer/unverified-already-implemented
           :review-feedback? (boolean (:task/review-feedback input))
           :verify-failures? (boolean (:task/verify-failures input))}}))

(defn- ^{:stratum 0} count-files-by-action
  "Count files matching a given action in an artifact."
  [action files]
  (count (filter #(= action (:action %)) files)))

(defn- ^{:stratum 0} code-artifact?
  "True when the structured artifact is a code artifact payload."
  [artifact]
  (and (map? artifact)
       (contains? artifact :code/files)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} metadata-only-submit?
  "True when MCP submit produced metadata but no file payload."
  [artifact-source structured-artifact]
  (and (= :mcp artifact-source)
       (map? structured-artifact)
       (not (code-artifact? structured-artifact))))

(defn- ^{:stratum 1} log-implementer-rejection
  "Emit a structured :implementer/response-rejected event when the
   implementer can't build a success response from the LLM turn.
   Surfaces WHY each iteration failed so the next dogfood reads the
   reject pattern (parse-failed, unverified-already-implemented,
   etc.) inline instead of forcing a deep-dive through the trace.

   Two reject paths today; both wrap response/error. The reject
   reason is a keyword the caller picks before invoking — keeps
   the event taxonomy stable as new reject paths get added.

   `input` is read for :repair-attempt? — that's the strongest
   signal for distinguishing 'agent stalled mid-narration' from
   'agent retried after rejection and still didn't ship.'"
  [logger reason input artifact-source content tools]
  (when logger
    (log/warn logger :implementer :implementer/response-rejected
              {:data {:reject/reason reason
                      :artifact-source artifact-source
                      :tools-called tools
                      :content-length (count (or content ""))
                      :repair-attempt? (boolean (repair-attempt? input))}})))

(defn- ^{:stratum 1} build-code-response
  "Build the success response for a parsed code artifact."
  [code context tokens cost-usd]
  (let [code-with-meta (-> code
                           (update :code/id #(or % (random-uuid)))
                           (assoc :code/language (impl-code/extract-language (:code/files code) context))
                           (assoc :code/created-at (java.util.Date.)))
        files (:code/files code-with-meta)]
    (response/success code-with-meta
                      {:tokens tokens
                       :metrics {:files-created (count-files-by-action :create files)
                                 :files-modified (count-files-by-action :modify files)
                                 :files-deleted (count-files-by-action :delete files)
                                 :language (:code/language code-with-meta)
                                 :tokens tokens
                                 :cost-usd cost-usd}})))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} process-llm-response
  "Normalize structured implementer outputs from any submission channel:
   worktree metadata, MCP artifact, file fallback, or parseable stdout.

   Emits a structured :implementer/response-rejected event with
   `:reject/reason` on both reject paths — when no channel yields a
   usable artifact (:parse-failed) AND when a repair attempt's
   `:already-implemented` claim lacks artifact evidence
   (:unverified-already-implemented). Surfaces WHY each iteration
   failed so the next dogfood reads the reject pattern inline
   instead of forcing a deep-dive trace read."
  [{:keys [content structured-artifact parsed-content derived-artifact
           artifact-source tokens cost-usd tools-called]}
   context logger input]
  (let [parsed (or structured-artifact parsed-content)
        tools tools-called
        metadata-only? (metadata-only-submit? artifact-source structured-artifact)]
    (when (nil? artifact-source)
      (log/warn logger :implementer :implementer/mcp-tool-not-called
                {:data {:content-length (count (or content ""))
                        :tools-called tools
                        :has-code-blocks? (boolean (re-find #"```" (or content "")))}}))
    (cond
      (code-artifact? structured-artifact)
      (build-code-response structured-artifact context tokens cost-usd)

      (= :already-implemented (:status parsed))
      (if (repair-attempt? input)
        (do (log-implementer-rejection
             logger :unverified-already-implemented
             input artifact-source content tools)
            (unverified-already-implemented-response input tokens cost-usd))
        (build-already-implemented-response parsed tokens cost-usd))

      metadata-only?
      (do
        (log-implementer-rejection
         logger :metadata-only-submit
         input artifact-source content tools)
        (response/error (messages/t :error/parse-failed)
                        {:tokens tokens
                         :metrics {:cost-usd cost-usd}
                         :data {:reject/reason :metadata-only-submit
                                :artifact-source artifact-source}}))

      :else
      ;; Prefer derived-artifact (code blocks extracted from text) over a
      ;; non-code parsed map. An agent that narrates "I created X" but never
      ;; calls Write produces a summary EDN with no :code/files key; treating
      ;; that as a code artifact yields a success with 0 files and silently
      ;; discards the work. Only fall back to parsed when it IS a code artifact.
      (let [code (or derived-artifact
                     (when (code-artifact? parsed) parsed))]
        (if code
          (build-code-response code context tokens cost-usd)
          (let [reject-reason (if (and (some? parsed) (not (code-artifact? parsed)))
                                :narrative-only-response
                                :parse-failed)]
            (log-implementer-rejection
             logger reject-reason
             input artifact-source content tools)
            (response/error (messages/t :error/parse-failed)
                            {:tokens tokens
                             :metrics {:cost-usd cost-usd}
                             :data   (cond-> {:reject/reason reject-reason}
                                       artifact-source
                                       (assoc :artifact-source artifact-source))})))))))
