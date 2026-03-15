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

   Layer 1 — depends on factory (Layer 0)."
  (:require [clojure.string :as str]
            [ai.miniforge.repo-index.factory :as factory]
            [ai.miniforge.repo-index.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0
;; Token estimation

(def ^:private chars-per-token 4)

(defn- estimate-tokens
  "Estimate token count for a string."
  [s]
  (int (Math/ceil (/ (count s) chars-per-token))))

;------------------------------------------------------------------------------ Layer 0
;; Formatting helpers

(defn- top-directory
  "Extract the top-level directory from a path, or '.' for root files."
  [path]
  (let [parts (str/split path #"/")]
    (if (> (count parts) 1) (first parts) ".")))

(defn- format-entry
  "Format a single RepoMapEntry as a markdown table row."
  [{:keys [path lang lines size]}]
  (str "| " path " | " (or lang "-") " | " lines " | " size " |"))

(defn- format-group-header
  "Format a directory group header with table columns."
  [dir file-count total-lines]
  (str "\n### " dir "/ (" file-count " files, " total-lines " lines)\n"
       (messages/t :repo-map/table-header) "\n"
       (messages/t :repo-map/table-separator)))

(defn- format-language-summary
  "Format language frequency map as a comma-separated summary."
  [languages]
  (->> (sort-by (comp - val) languages)
       (map (fn [[k v]] (str k " (" v ")")))
       (str/join ", ")))

;------------------------------------------------------------------------------ Layer 1
;; Budget-aware group rendering

(defn- render-group-rows
  "Render as many rows as fit within the remaining token budget.
   Returns {:rows [...] :row-tokens n}."
  [tokens header-tokens group-entries remaining-budget]
  (reduce
    (fn [{:keys [row-tokens] :as acc} entry]
      (let [row (format-entry entry)
            rt (+ row-tokens (estimate-tokens row) 1)]
        (if (> (+ tokens header-tokens rt) remaining-budget)
          (reduced acc)
          (-> acc (update :rows conj row) (assoc :row-tokens rt)))))
    {:rows [] :row-tokens 0}
    group-entries))

(defn- fit-partial-group
  "Try to fit as many entries from a group as the budget allows."
  [acc group-header group-entries remaining-budget]
  (let [{:keys [tokens shown]} acc
        header-tokens (estimate-tokens group-header)
        {:keys [rows row-tokens]} (render-group-rows tokens header-tokens
                                                     group-entries remaining-budget)]
    (if (seq rows)
      (factory/render-acc-with
        (str (:text acc) "\n" group-header "\n" (str/join "\n" rows))
        (+ tokens header-tokens row-tokens)
        (+ shown (count rows))
        true)
      (assoc acc :truncated? true))))

(defn- append-full-group
  "Append a complete directory group to the render accumulator."
  [acc group-text group-tokens group-entry-count]
  (factory/render-acc-with
    (str (:text acc) "\n" group-text)
    (+ (:tokens acc) group-tokens)
    (+ (:shown acc) group-entry-count)
    false))

(defn- render-group-text
  "Render a complete directory group as markdown text."
  [group-header group-entries]
  (str group-header "\n" (str/join "\n" (map format-entry group-entries))))

(defn- render-directory-group
  "Render one directory group into the accumulator, respecting the budget."
  [remaining-budget grouped]
  (fn [{:keys [tokens truncated?] :as acc} dir]
    (if truncated?
      acc
      (let [group-entries (get grouped dir)
            group-lines (reduce + 0 (map :lines group-entries))
            group-header (format-group-header dir (count group-entries) group-lines)
            group-text (render-group-text group-header group-entries)
            group-tokens (estimate-tokens group-text)]
        (if (> (+ tokens group-tokens) remaining-budget)
          (fit-partial-group acc group-header group-entries remaining-budget)
          (append-full-group acc group-text group-tokens (count group-entries)))))))

;------------------------------------------------------------------------------ Layer 1
;; Repo map header

(defn- build-header
  "Build the repo map header with summary statistics."
  [index entry-count total-entry-lines]
  (str "## " (messages/t :repo-map/header-title) "\n"
       (messages/t :repo-map/tree-label) ": " (:tree-sha index) "\n"
       (messages/t :repo-map/files-label) ": " entry-count " | "
       (messages/t :repo-map/lines-label) ": " total-entry-lines " | "
       (messages/t :repo-map/languages-label) ": "
       (format-language-summary (:languages index)) "\n"))

;------------------------------------------------------------------------------ Layer 2
;; Public API

(def default-token-budget 500)

(defn- prepare-entries
  "Filter, sort, and convert files to RepoMapEntry maps."
  [files exclude-gen?]
  (->> (cond->> files exclude-gen? (remove :generated?))
       (sort-by :path)
       (mapv factory/->repo-map-entry)))

(defn- walk-directory-groups
  "Walk directory groups accumulating text within the token budget."
  [remaining-budget grouped dir-order]
  (reduce (render-directory-group remaining-budget grouped)
          (factory/->render-acc)
          dir-order))

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
         entries (prepare-entries (:files index) exclude-gen?)
         grouped (group-by #(top-directory (:path %)) entries)
         dir-order (sort (keys grouped))
         total-entry-lines (reduce + 0 (map :lines entries))
         header (build-header index (count entries) total-entry-lines)
         header-tokens (estimate-tokens header)
         remaining-budget (- budget header-tokens)
         result (walk-directory-groups remaining-budget grouped dir-order)
         full-text (str header (:text result))
         total-tokens (+ header-tokens (:tokens result))
         shown (:shown result)
         truncated? (:truncated? result)
         final-entries (if truncated? (vec (take shown entries)) entries)]
     (factory/->repo-map-slice (:tree-sha index) final-entries full-text
                               (count entries) shown truncated? total-tokens))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (require '[ai.miniforge.repo-index.scanner :as scanner])
  (def idx (scanner/scan "."))

  (def rmap (generate idx))
  (:token-estimate rmap)
  (:shown-files rmap)
  (:truncated? rmap)
  (println (:text rmap))

  :leave-this-here)
