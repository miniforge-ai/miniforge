;; Copyright 2025 miniforge.ai
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

(ns ai.miniforge.tui-engine.widget.tree
  "Tree view and kanban column widgets.

   Provides hierarchical tree navigation and kanban-style column displays.
   Pure functions with no side effects."
  (:require
   [ai.miniforge.tui-engine.layout.buffer :as buf]))

;------------------------------------------------------------------------------ Layer 2
;; Tree view

(defn tree
  "Render a tree with expand/collapse.
   Options:
   - :nodes    - flat vector of {:label str :depth int :expandable? bool}
   - :expanded - set of indices that are expanded
   - :selected - index of selected node
   - :fg, :selected-fg, :selected-bg - colors"
  [[cols rows] & [{:keys [nodes expanded selected fg selected-fg selected-bg]
                    :or {expanded #{} selected 0 fg :white
                         selected-fg :white selected-bg :blue}}]]
  (let [buffer (buf/make-buffer [cols rows])
        visible (take rows (or nodes []))]
    (reduce (fn [b [idx {:keys [label depth expandable?]}]]
              (let [sel? (= idx selected)
                    indent (* 2 (or depth 0))
                    icon (cond
                           (and expandable? (contains? expanded idx)) "▼ "
                           expandable? "▸ "
                           :else "  ")
                    text (str (apply str (repeat indent \space)) icon (or label ""))
                    text (subs text 0 (min (count text) cols))]
                (buf/buf-put-string b 0 idx text
                                       {:fg (if sel? selected-fg fg)
                                        :bg (if sel? selected-bg :black)
                                        :bold? sel?})))
            buffer
            (map-indexed vector visible))))

;------------------------------------------------------------------------------ Layer 3
;; Kanban columns

(def ^:private status-chars
  {:running  \●
   :success  \✓
   :failed   \✗
   :blocked  \◐
   :pending  \○
   :skipped  \─
   :spinning \⟳})

(def ^:private status-colors
  {:running  :cyan
   :success  :green
   :failed   :red
   :blocked  :yellow
   :pending  :default
   :skipped  :default
   :spinning :cyan})

(defn kanban
  "Render kanban-style columns.
   Options:
   - :columns - [{:title str :color kw :cards [{:label str :status kw}]}]
   Each column gets equal width."
  [[cols rows] & [{:keys [columns]}]]
  (let [n (max 1 (count (or columns [])))
        col-width (max 4 (quot cols n))
        buffer (buf/make-buffer [cols rows])]
    (reduce (fn [b [idx {:keys [title color cards]}]]
              (let [x-offset (* idx col-width)
                    avail-w (min col-width (- cols x-offset))
                    ;; Header
                    header (subs (str " " title (apply str (repeat avail-w \space)))
                                 0 (min avail-w (+ (count title) 2)))
                    b (buf/buf-put-string b x-offset 0 header
                                             {:fg (or color :white) :bg :black :bold? true})
                    ;; Separator
                    b (if (>= rows 2)
                        (buf/buf-put-string b x-offset 1
                                               (apply str (repeat avail-w \─))
                                               {:fg :default :bg :black :bold? false})
                        b)]
                ;; Cards
                (reduce (fn [b2 [card-idx {:keys [label status]}]]
                          (let [r (+ card-idx 2)]
                            (if (>= r rows) (reduced b2)
                                (let [indicator (get status-chars status \○)
                                      line (str indicator " " (subs label 0 (min (count label) (- avail-w 2))))
                                      line (subs line 0 (min (count line) avail-w))]
                                  (buf/buf-put-string b2 x-offset r line
                                                         {:fg (get status-colors status :white)
                                                          :bg :black :bold? false})))))
                        b
                        (map-indexed vector (or cards [])))))
            buffer
            (map-indexed vector (or columns [])))))
