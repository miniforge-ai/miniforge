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

(ns ai.miniforge.repo-index.repo-map
  "Generate token-budgeted repo maps from a repo index.

   Layer 1 — depends on scanner output (RepoIndex)."
  (:require [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Token estimation

(def ^:private chars-per-token
  "Rough estimate: ~4 characters per token for code-like content."
  4)

(defn- estimate-tokens
  "Estimate token count for a string."
  [s]
  (int (Math/ceil (/ (count s) chars-per-token))))

;------------------------------------------------------------------------------ Layer 0
;; Formatting

(defn- top-directory
  "Extract the top-level directory from a path, or '.' for root files."
  [path]
  (let [parts (str/split path #"/")]
    (if (> (count parts) 1)
      (first parts)
      ".")))

(defn- format-entry
  "Format a single file entry as a table row."
  [{:keys [path lang lines size]}]
  (str "| " path " | " (or lang "-") " | " lines " | " size " |"))

(defn- format-group-header
  "Format a directory group header."
  [dir file-count total-lines]
  (str "\n### " dir "/ (" file-count " files, " total-lines " lines)\n"
       "| Path | Lang | Lines | Size |\n"
       "|------|------|------:|-----:|"))

;------------------------------------------------------------------------------ Layer 1
;; Repo map generation

(def default-token-budget
  "Default token budget for repo map (configurable)."
  500)

(defn generate
  "Generate a token-budgeted repo map from a repo index.

   Arguments:
   - index - RepoIndex map from scanner/scan
   - opts  - Optional map:
     - :token-budget  - Max tokens for the map (default 500)
     - :exclude-generated? - Exclude generated files (default true)

   Returns:
   - RepoMapSlice with :entries, :text (rendered markdown), :token-estimate"
  ([index] (generate index {}))
  ([index opts]
   (let [budget (or (:token-budget opts) default-token-budget)
         exclude-gen? (get opts :exclude-generated? true)
         files (cond->> (:files index)
                 exclude-gen? (remove :generated?))
         ;; Sort by directory grouping, then path
         sorted (sort-by :path files)
         ;; Build entries
         entries (mapv (fn [{:keys [path language lines size]}]
                         {:path path
                          :lang language
                          :lines lines
                          :size size})
                       sorted)
         ;; Group by top directory
         grouped (group-by #(top-directory (:path %)) entries)
         dir-order (sort (keys grouped))
         ;; Build text incrementally, respecting token budget
         header (str "## Repository Map\n"
                     "Tree: " (:tree-sha index) "\n"
                     "Files: " (count entries) " | "
                     "Lines: " (reduce + 0 (map :lines entries)) " | "
                     "Languages: " (str/join ", " (map (fn [[k v]] (str k " (" v ")"))
                                                       (sort-by (comp - val) (:languages index))))
                     "\n")
         header-tokens (estimate-tokens header)
         remaining-budget (- budget header-tokens)
         ;; Walk directory groups, accumulating text until budget is reached
         result (reduce
                  (fn [{:keys [text tokens shown truncated?] :as acc} dir]
                    (if truncated?
                      acc
                      (let [group-entries (get grouped dir)
                            group-lines (reduce + 0 (map :lines group-entries))
                            group-header (format-group-header dir (count group-entries) group-lines)
                            group-rows (map format-entry group-entries)
                            group-text (str group-header "\n" (str/join "\n" group-rows))
                            group-tokens (estimate-tokens group-text)
                            new-tokens (+ tokens group-tokens)]
                        (if (> new-tokens remaining-budget)
                          ;; Over budget — try to fit partial group
                          (let [partial-entries (reduce
                                                 (fn [{:keys [row-tokens] :as pacc} entry]
                                                   (let [row (format-entry entry)
                                                         rt (+ row-tokens (estimate-tokens row) 1)]
                                                     (if (> (+ tokens (estimate-tokens group-header) rt) remaining-budget)
                                                       (reduced pacc)
                                                       (-> pacc
                                                           (update :rows conj row)
                                                           (assoc :row-tokens rt)))))
                                                 {:rows [] :row-tokens 0}
                                                 group-entries)]
                            (if (seq (:rows partial-entries))
                              {:text (str text "\n" group-header "\n" (str/join "\n" (:rows partial-entries)))
                               :tokens (+ tokens (estimate-tokens group-header) (:row-tokens partial-entries))
                               :shown (+ shown (count (:rows partial-entries)))
                               :truncated? true}
                              (assoc acc :truncated? true)))
                          ;; Fits in budget
                          {:text (str text "\n" group-text)
                           :tokens new-tokens
                           :shown (+ shown (count group-entries))
                           :truncated? false}))))
                  {:text "" :tokens 0 :shown 0 :truncated? false}
                  dir-order)
         full-text (str header (:text result))
         total-tokens (+ header-tokens (:tokens result))]
     {:tree-sha (:tree-sha index)
      :entries (if (:truncated? result)
                 (vec (take (:shown result) entries))
                 entries)
      :text full-text
      :total-files (count entries)
      :shown-files (:shown result)
      :truncated? (:truncated? result)
      :token-estimate total-tokens})))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (require '[ai.miniforge.repo-index.scanner :as scanner])
  (def idx (scanner/scan "/Users/chris/Local/miniforge.ai/miniforge"))

  ;; Generate with default budget
  (def rmap (generate idx))
  (:token-estimate rmap)
  (:shown-files rmap)
  (:truncated? rmap)
  (println (:text rmap))

  ;; Generate with larger budget
  (def rmap-big (generate idx {:token-budget 2000}))
  (:shown-files rmap-big)

  :leave-this-here)
