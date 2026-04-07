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

(ns ai.miniforge.tui-views.update.mode
  "Mode switching and command buffer manipulation.

   Pure functions for switching between normal/command/search/filter modes.
   Layer 3."
  (:require
   [clojure.string :as str]
   [ai.miniforge.tui-views.model :as model]
   [ai.miniforge.tui-views.transition :as transition]
   [ai.miniforge.tui-views.update.filter :as filter]))

;------------------------------------------------------------------------------ Layer 3
;; Mode switching

(defn enter-command-mode [model]
  (transition/enter-command model))

(defn enter-search-mode [model]
  (transition/enter-search model))

(defn exit-mode
  "Exit command/search mode back to normal.
   Clears command buffer, search results, filtered-indices, and search-matches."
  [model]
  (assoc model :mode :normal :command-buf "" :search-results []
         :filtered-indices nil :search-matches [] :search-match-idx nil))

(defn confirm-search
  "Confirm search: exit to normal mode but KEEP results.
   For aggregate views: keeps filtered-indices for browse/select.
   For detail views: keeps search-matches for n/N navigation.
   Jumps to first match if any exist."
  [model]
  (let [m (assoc model :mode :normal :command-buf "" :search-results [])]
    (if (seq (:search-matches m))
      ;; Detail view: jump to first match
      (let [first-match (get (:search-matches m) 0)]
        (assoc m :search-match-idx 0
                 :scroll-offset (:line-idx first-match 0)))
      m)))

(defn command-append [model ch]
  (update model :command-buf str ch))

(defn command-backspace [model]
  (let [buf (:command-buf model)]
    (if (> (count buf) 1)
      (assoc model :command-buf (subs buf 0 (dec (count buf))))
      (exit-mode model))))

;------------------------------------------------------------------------------ Layer 4
;; Search filtering

(defn search-lines
  "Find line indices matching query in a vector of strings.
   Returns vector of {:line-idx N :text line}."
  [lines query]
  (let [q (str/lower-case query)]
    (into []
      (keep-indexed
        (fn [idx line]
          (when (str/includes? (str/lower-case (or line "")) q)
            {:line-idx idx :text line})))
      lines)))

(defn matching-indices
  "Return set of indices whose item matches query via name-fn."
  [items name-fn query]
  (->> items
       (map-indexed (fn [idx item] [idx (or (name-fn item) "")]))
       (filter (fn [[_ label]] (str/includes? (str/lower-case label) query)))
       (map first)
       set))

(defn compute-aggregate-search
  "Filter items by name match. Sets :filtered-indices."
  [model query items name-fn]
  (if (str/blank? query)
    (assoc model :filtered-indices nil :search-matches [])
    (-> model
        (assoc :filtered-indices (matching-indices items name-fn (str/lower-case query))
               :selected-idx 0
               :search-matches []))))

(defn compute-detail-search
  "Find matches in scrollable content. Sets :search-matches."
  [model query lines]
  (if (str/blank? query)
    (assoc model :search-matches [] :search-match-idx nil)
    (let [matches (search-lines lines query)]
      (assoc model :search-matches matches
                   :search-match-idx (when (seq matches) 0)
                   :filtered-indices nil))))

(defn evidence-labels
  "Build searchable label strings from evidence tree."
  [model]
  (let [phases (get-in model [:detail :phases] [])
        evidence (get-in model [:detail :evidence])]
    (into []
      (concat
        ["Intent"
         (or (get-in evidence [:intent :description]) "")]
        ["Phases"]
        (map #(str (name (:phase %)) " " (name (get % :status :pending))) phases)
        ["Validation"
         (if (get-in evidence [:validation :passed?])
           "All gates passed" "Errors")]
        ["Policy"
         (if (get-in evidence [:policy :compliant?])
           "Policy compliant" "Policy violations")]))))

(defn compute-evidence-search
  "Search evidence tree node labels."
  [model query]
  (compute-detail-search model query (evidence-labels model)))

(def workflow-field-names
  "Recognized field qualifiers for workflow search."
  #{"status" "name" "phase" "error"})

(defn parse-workflow-query
  "Parse a workflow search query into {:fields {kw [vals]} :text str}.
   Supports field:value tokens like status:failed, name:deploy."
  [query]
  (let [tokens (remove str/blank? (str/split (str/trim (or query "")) #"\s+"))]
    (reduce
     (fn [acc token]
       (if (str/includes? token ":")
         (let [[field value] (str/split token #":" 2)]
           (if (contains? workflow-field-names field)
             (update-in acc [:fields (keyword field)] (fnil conj []) value)
             (update acc :text (fn [t] (if (str/blank? t) token (str t " " token))))))
         (update acc :text (fn [t] (if (str/blank? t) token (str t " " token))))))
     {:fields {} :text ""}
     tokens)))

(defn workflow-field-value
  "Extract the string value for a field from a workflow."
  [wf field-kw]
  (case field-kw
    :status (some-> (:status wf) name)
    :name   (:name wf)
    :phase  (some-> (:phase wf) name)
    :error  (get wf :error "")
    ""))

(defn workflow-matches-query?
  "Test if a workflow matches a parsed query.
   AND across fields, OR within same field, free-text matches name."
  [wf parsed]
  (let [{:keys [fields text]} parsed
        text-match? (or (str/blank? text)
                        (str/includes? (str/lower-case (get wf :name ""))
                                       (str/lower-case text)))
        fields-match? (every? (fn [[field-kw values]]
                                (let [val (str/lower-case (or (workflow-field-value wf field-kw) ""))]
                                  (some #(str/includes? val (str/lower-case %)) values)))
                              fields)]
    (and text-match? fields-match?)))

(defn compute-workflow-search
  "Faceted search over workflows. Supports status:failed, name:deploy, etc."
  [model query workflows]
  (if (str/blank? query)
    (assoc model :filtered-indices nil :search-matches [])
    (let [parsed (parse-workflow-query query)
          indices (into #{} (keep-indexed
                              (fn [idx wf]
                                (when (workflow-matches-query? wf parsed)
                                  idx)))
                        workflows)]
      (assoc model :filtered-indices indices :selected-idx 0 :search-matches []))))

(defn compute-search-results
  "Dispatch search by current view.
   Aggregate views: filter list items via :filtered-indices.
   Detail views: find-in-page via :search-matches."
  [model]
  (let [query (subs (:command-buf model) 1) ;; strip leading "/"
        view (:view model)]
    (case view
      ;; Aggregate views — filter the list
      :workflow-list (compute-workflow-search model query (:workflows model))
      :pr-fleet      (compute-aggregate-search model query (:pr-items model)
                       #(or (:pr/title %) (:name %)))
      :train-view    (compute-aggregate-search model query
                       (get-in model [:detail :selected-train :train/prs] [])
                       #(or (:pr/title %) (:name %)))
      :repo-manager  (compute-aggregate-search model query
                       (model/repo-manager-items model)
                       identity)

      ;; Detail views — find-in-page
      :workflow-detail
      (let [output (get-in model [:detail :agent-output] "")
            lines (str/split-lines output)]
        (compute-detail-search model query lines))

      :evidence
      (compute-evidence-search model query)

      :artifact-browser
      (compute-aggregate-search model query
        (get-in model [:detail :artifacts] [])
        #(or (:name %) (:path %) ""))

      ;; Default — no search behavior
      model)))

;------------------------------------------------------------------------------ Layer 5
;; Filter mode (VS Code cmd+p style palette for PR fleet)

(defn enter-filter-mode
  "Enter filter mode. Sets mode to :filter with '>' prefix in command bar."
  [model]
  (if (= :pr-fleet (:view model))
    (transition/enter-filter model)
    model))

(defn filter-query
  "Extract the filter query from the command buffer (strip '>' prefix)."
  [model]
  (let [buf (get model :command-buf ">")]
    (if (str/starts-with? buf ">")
      (subs buf 1)
      buf)))

(defn update-filter-results
  "Recompute filtered indices from the current filter query."
  [model]
  (let [query (filter-query model)
        indices (filter/compute-filter-indices (:pr-items model []) query)]
    (assoc model
           :filtered-indices indices
           :selected-idx 0)))

(defn filter-confirm
  "Confirm filter: exit to normal mode, keep filter active.
   Sets :active-filter for display in the header."
  [model]
  (let [query (filter-query model)]
    (-> model
        (assoc :mode :normal :command-buf "")
        (assoc :active-filter (when-not (str/blank? query) query)))))

(defn filter-escape
  "Escape filter: exit to normal mode, clear filter entirely."
  [model]
  (-> model
      (assoc :mode :normal :command-buf ""
             :filtered-indices nil :selected-idx 0
             :active-filter nil)))

(defn filter-append
  "Append character to filter buffer and recompute results."
  [model ch]
  (-> model
      (update :command-buf str ch)
      update-filter-results))

(defn filter-backspace
  "Backspace in filter buffer. Exit mode if at '>' prefix."
  [model]
  (let [buf (:command-buf model)]
    (if (> (count buf) 1)
      (-> model
          (assoc :command-buf (subs buf 0 (dec (count buf))))
          update-filter-results)
      (filter-escape model))))

