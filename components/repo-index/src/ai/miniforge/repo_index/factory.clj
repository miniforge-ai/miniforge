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

(ns ai.miniforge.repo-index.factory
  "Factory functions for repo-index domain maps.

   Single source of truth for constructing FileRecord, RepoIndex,
   RepoMapEntry, RepoMapSlice, FileContent, and render accumulator maps.

   Layer 0 — pure data construction, no I/O or side effects.")

;------------------------------------------------------------------------------ Layer 0
;; FileRecord

(defn ->file-record
  "Create a FileRecord map."
  [path blob-sha size lines language generated?]
  {:path path
   :blob-sha blob-sha
   :size size
   :lines lines
   :language language
   :generated? generated?})

;------------------------------------------------------------------------------ Layer 0
;; RepoIndex

(defn ->repo-index
  "Create a RepoIndex map from entries and computed language frequencies."
  [tree-sha repo-root entries languages]
  {:tree-sha tree-sha
   :repo-root repo-root
   :files entries
   :file-count (count entries)
   :total-lines (reduce + 0 (map :lines entries))
   :languages languages
   :indexed-at (java.util.Date.)})

;------------------------------------------------------------------------------ Layer 0
;; RepoMapEntry

(defn ->repo-map-entry
  "Create a RepoMapEntry map from a FileRecord."
  [{:keys [path language lines size]}]
  {:path path
   :lang language
   :lines lines
   :size size})

;------------------------------------------------------------------------------ Layer 0
;; RepoMapSlice

(defn ->repo-map-slice
  "Create a RepoMapSlice result map."
  [tree-sha entries text total-files shown-files truncated? token-estimate]
  {:tree-sha tree-sha
   :entries entries
   :text text
   :total-files total-files
   :shown-files shown-files
   :truncated? truncated?
   :token-estimate token-estimate})

;------------------------------------------------------------------------------ Layer 0
;; FileContent (returned by get-file)

(defn ->file-content
  "Create a file-content result map."
  [path content lines truncated?]
  {:path path
   :content content
   :lines lines
   :truncated? truncated?})

;------------------------------------------------------------------------------ Layer 0
;; Render accumulator (used internally by repo-map generation)

(defn ->render-acc
  "Create the initial render accumulator."
  []
  {:text "" :tokens 0 :shown 0 :truncated? false})

(defn render-acc-with
  "Create a render accumulator with updated fields."
  [text tokens shown truncated?]
  {:text text :tokens tokens :shown shown :truncated? truncated?})

;------------------------------------------------------------------------------ Layer 0
;; Search domain maps

(defn ->snippet
  "Create a search result snippet map."
  [start-line end-line text]
  {:start-line start-line
   :end-line end-line
   :text text})

(defn ->search-hit
  "Create a search hit result map."
  [path score snippets]
  {:path path
   :score score
   :snippets snippets})

(defn ->doc-entry
  "Create a document entry for the search inverted index."
  [path token-count term-freqs content]
  {:path path
   :token-count token-count
   :term-freqs term-freqs
   :content content})

(defn ->inverted-index
  "Create an empty inverted index accumulator."
  []
  {:term->doc-ids {}
   :doc-freq {}})

(defn ->search-index
  "Create a SearchIndex map from docs, corpus stats, and inverted index."
  [doc-map doc-count avg-doc-length term->doc-ids doc-freq]
  {:docs doc-map
   :doc-count doc-count
   :avg-doc-length avg-doc-length
   :term->doc-ids term->doc-ids
   :doc-freq doc-freq})
