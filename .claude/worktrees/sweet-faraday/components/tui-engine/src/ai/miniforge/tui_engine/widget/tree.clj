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

(ns ai.miniforge.tui-engine.widget.tree
  "Tree view and kanban column widgets.

   Provides hierarchical tree navigation and kanban-style column displays.
   Pure functions with no side effects."
  (:require
   [ai.miniforge.tui-engine.layout.buffer :as buf]))

;------------------------------------------------------------------------------ Layer 2
;; Tree view

(defn- render-tree-node
  "Render a single tree node into the buffer at the given row."
  [b [row-idx node] {:keys [cols scroll-offset expanded selected
                             fg selected-fg selected-bg]}]
  (let [node-idx (+ row-idx scroll-offset)
        {:keys [label depth expandable?]} node
        node-fg (or (:fg node) fg)
        sel? (= node-idx selected)
        indent (* 2 (or depth 0))
        icon (cond
               (and expandable? (contains? expanded node-idx)) "▼ "
               expandable? "▸ "
               :else "  ")
        text (str (apply str (repeat indent \space)) icon (or label ""))
        text (subs text 0 (min (count text) cols))]
    (buf/buf-put-string b 0 row-idx text
                           {:fg (if sel? selected-fg node-fg)
                            :bg (if sel? selected-bg :default)
                            :bold? sel?})))

(defn tree
  "Render a tree with expand/collapse.
   Options:
   - :nodes    - flat vector of {:label str :depth int :expandable? bool :fg color-kw}
   - :expanded - set of indices that are expanded
   - :selected - index of selected node
   - :scroll-offset - number of nodes to skip from the top (default 0)
   - :fg, :selected-fg, :selected-bg - colors
   Per-node :fg overrides the widget-level :fg for that node."
  [[cols rows] & [{:keys [nodes expanded selected scroll-offset
                           fg selected-fg selected-bg]
                    :or {expanded #{} selected 0 scroll-offset 0 fg :white
                         selected-fg :white selected-bg :blue}}]]
  (let [buffer (buf/make-buffer [cols rows])
        all-nodes (or nodes [])
        visible (->> all-nodes (drop scroll-offset) (take rows))
        opts {:cols cols :scroll-offset scroll-offset :expanded expanded
              :selected selected :fg fg :selected-fg selected-fg
              :selected-bg selected-bg}]
    (reduce (fn [b row-and-node] (render-tree-node b row-and-node opts))
            buffer
            (map-indexed vector visible))))

;------------------------------------------------------------------------------ Layer 3
;; Kanban columns

(def status-chars
  {:running  \●
   :success  \✓
   :failed   \✗
   :blocked  \◐
   :pending  \○
   :skipped  \─
   :spinning \⟳})

(def status-colors
  {:running  :cyan
   :success  :green
   :failed   :red
   :blocked  :yellow
   :pending  :default
   :skipped  :default
   :spinning :cyan})

(defn render-column-header
  "Render column header with title."
  [buffer x-offset avail-w title color]
  (let [header (subs (str " " title (apply str (repeat avail-w \space)))
                     0 (min avail-w (+ (count title) 2)))]
    (buf/buf-put-string buffer x-offset 0 header
                        {:fg (or color :white) :bg :default :bold? true})))

(defn render-column-separator
  "Render horizontal separator below header."
  [buffer x-offset avail-w rows]
  (if (>= rows 2)
    (buf/buf-put-string buffer x-offset 1
                        (apply str (repeat avail-w \─))
                        {:fg :default :bg :default :bold? false})
    buffer))

(defn format-card-line
  "Format card text with status indicator."
  [label status avail-w]
  (let [indicator (get status-chars status \○)
        line (str indicator " " (subs label 0 (min (count label) (- avail-w 2))))]
    (subs line 0 (min (count line) avail-w))))

(defn render-card
  "Render single card in column."
  [buffer x-offset card-idx label status avail-w rows]
  (let [r (+ card-idx 2)]
    (if (>= r rows)
      (reduced buffer)
      (let [line (format-card-line label status avail-w)]
        (buf/buf-put-string buffer x-offset r line
                            {:fg (get status-colors status :white)
                             :bg :default :bold? false})))))

(defn render-column
  "Render complete kanban column."
  [buffer idx {:keys [title color cards]} col-width cols rows]
  (let [x-offset (* idx col-width)
        avail-w (min col-width (- cols x-offset))
        buffer (render-column-header buffer x-offset avail-w title color)
        buffer (render-column-separator buffer x-offset avail-w rows)]
    (reduce (fn [b [card-idx {:keys [label status]}]]
              (render-card b x-offset card-idx label status avail-w rows))
            buffer
            (map-indexed vector (or cards [])))))

(defn kanban
  "Render kanban-style columns.
   Options:
   - :columns - [{:title str :color kw :cards [{:label str :status kw}]}]
   Each column gets equal width."
  [[cols rows] & [{:keys [columns]}]]
  (let [n (max 1 (count (or columns [])))
        col-width (max 4 (quot cols n))
        buffer (buf/make-buffer [cols rows])]
    (reduce (fn [b [idx column]]
              (render-column b idx column col-width cols rows))
            buffer
            (map-indexed vector (or columns [])))))
