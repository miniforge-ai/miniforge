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
        artifact-source (cond
                          worktree-artifact :worktree-metadata
                          artifact :mcp
                          fallback-artifact :file-fallback
                          :else nil)
        structured-artifact (or worktree-artifact artifact fallback-artifact)
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
