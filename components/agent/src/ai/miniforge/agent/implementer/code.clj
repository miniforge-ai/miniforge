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
(ns ai.miniforge.agent.implementer.code
  "Implementer code artifacts: their schemas, parsing the agent's
   response and its code blocks into an artifact, validation, language
   detection, and summaries."
  (:require
   [ai.miniforge.agent.implementer.language :as impl-language]
   [ai.miniforge.patterns.interface :as patterns]
   [ai.miniforge.schema.interface :as schema]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

;; Implementer-specific schemas
(def ^{:stratum 0} CodeFile
  [:map
   [:path [:string {:min 1}]]
   [:content [:string {:min 0}]]
   [:action [:enum :create :modify :delete]]])

;; Validation
(defn- ^{:stratum 0} find-blank-creates
  "Find :create files with blank content."
  [files]
  (filter (fn [f] (and (= :create (:action f)) (str/blank? (:content f)))) files))

(defn- ^{:stratum 0} find-duplicate-paths
  "Find file paths that appear more than once."
  [files]
  (->> (map :path files)
       frequencies
       (filter #(> (val %) 1))))

;; Language detection
(defn ^{:stratum 0} extract-extension
  "Extract file extension from a path."
  [path]
  (last (re-find #"\.(\w+)$" path)))

;; Response parsing
(defn- ^{:stratum 0} try-parse-edn-block
  "Try to extract an EDN map from a fenced code block."
  [text]
  (when-let [match (re-find #"```(?:clojure|edn)?\s*\n([\s\S]*?)\n```" text)]
    (let [parsed (edn/read-string (second match))]
      (when (map? parsed) parsed))))

(defn- ^{:stratum 0} try-parse-raw-edn
  "Try to parse the entire text as EDN, returning a map or nil."
  [text]
  (try
    (let [r (edn/read-string text)]
      (when (map? r) r))
    (catch Exception _ nil)))

(defn- ^{:stratum 0} try-parse-inline-status
  "Scan for an inline {:status :already-implemented ...} EDN map."
  [text]
  (when-let [match (re-find #"\{:status\s+:already-implemented[^}]*\}" text)]
    (edn/read-string match)))

(defn- ^{:stratum 0} extract-path-from-context
  "Try to extract a file path from text immediately preceding a code block.
   Looks for common patterns like '### path/to/file.clj' or '**path/to/file.clj**'
   or 'File: path/to/file.clj' in the preceding lines."
  [text block-start]
  (let [preceding (subs text 0 block-start)
        ;; Take last 3 lines before the code block
        lines (take-last 3 (str/split-lines preceding))]
    (some (fn [line]
            (or
             (second (re-find patterns/md-heading-file-path line))
             (second (re-find patterns/md-delimited-file-path line))
             (second (re-find patterns/md-label-file-path line))))
          lines)))

(defn ^{:stratum 0} code-summary
  [artifact]
  {:id (:code/id artifact)
   :file-count (count (:code/files artifact))
   :actions (frequencies (map :action (:code/files artifact)))
   :language (:code/language artifact)
   :tests-needed? (:code/tests-needed? artifact)
   :dependencies-added (count (:code/dependencies-added artifact []))})

(defn ^{:stratum 0} files-by-action
  [artifact]
  (group-by :action (:code/files artifact)))

(defn ^{:stratum 0} total-lines
  [artifact]
  (->> (:code/files artifact)
       (remove #(= :delete (:action %)))
       (map :content)
       (map #(count (str/split-lines %)))
       (reduce + 0)))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} CodeArtifact
  [:map
   [:code/id uuid?]
   [:code/files [:vector CodeFile]]
   [:code/dependencies-added {:optional true} [:vector [:string {:min 1}]]]
   [:code/tests-needed? {:optional true} boolean?]
   [:code/language {:optional true} [:string {:min 1}]]
   [:code/summary {:optional true} [:string {:min 1}]]
   [:code/created-at {:optional true} inst?]])

(defn ^{:stratum 1} parse-code-response
  "Parse the LLM response to extract code artifact.
   Handles EDN in code blocks, plain EDN, and inline EDN maps.
   Returns nil if no parseable map is found."
  [response-content]
  (try
    (when-let [parsed (or (try-parse-edn-block response-content)
                          (try-parse-raw-edn response-content)
                          (try-parse-inline-status response-content))]
      (when (map? parsed) parsed))
    (catch Exception _
      nil)))

(defn ^{:stratum 1} extract-code-blocks
  "Extract code blocks from markdown response and convert to file list.
   Tries to detect file paths from markdown headings before each block.

   Pathless blocks are not repo artifacts. Treating them as generated
   filenames lets narrative snippets pass implement, verify, and review
   without producing a releasable diff."
  [response-content]
  (let [pattern patterns/md-code-block
        matcher (re-matcher pattern response-content)]
    (loop [results [] idx 0]
      (if (.find matcher)
        (let [content (.group matcher 2)
              block-start (.start matcher)
              path (extract-path-from-context response-content block-start)]
          (if path
            (recur (conj results {:path path
                                  :content (str/trim content)
                                  :action :create})
                   (inc idx))
            nil))
        (when (seq results) results)))))

(defn ^{:stratum 1} extract-language
  "Extract the programming language from file paths or context."
  [files context]
  (let [extensions (->> files
                        (map :path)
                        (keep extract-extension)
                        frequencies)
        most-common (when (seq extensions)
                      (key (apply max-key val extensions)))]
    (or (:language context)
        (get @impl-language/ext->language most-common most-common))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} validate-code-artifact
  [artifact]
  (if-not (m/validate CodeArtifact artifact)
    (schema/invalid (schema/explain CodeArtifact artifact)
                    {:errors (schema/explain CodeArtifact artifact)})
    (let [files (:code/files artifact)
          blank-creates (find-blank-creates files)
          duplicate-paths (find-duplicate-paths files)]
      (cond
        (seq blank-creates)
        (schema/invalid (str "Blank content for :create files: " (mapv :path blank-creates))
                        {:errors {:files (str "Blank content for :create files: "
                                              (mapv :path blank-creates))}})
        (seq duplicate-paths)
        (schema/invalid (str "Duplicate file paths: " (keys duplicate-paths))
                        {:errors {:files (str "Duplicate file paths: " (keys duplicate-paths))}})
        :else
        (schema/valid)))))

(defn ^{:stratum 2} code-from-blocks
  "Build a code artifact map from extracted markdown code blocks."
  [content]
  (when-let [files (extract-code-blocks content)]
    {:code/id (random-uuid)
     :code/files files
     :code/tests-needed? true
     :code/summary "Implementation from code blocks"
     :code/created-at (java.util.Date.)}))
