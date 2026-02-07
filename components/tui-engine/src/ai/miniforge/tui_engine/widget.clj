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

(ns ai.miniforge.tui-engine.widget
  "Composite TUI widgets built on layout primitives.

   Each widget is a pure function: (widget-fn [cols rows] opts) -> cell-buffer.
   Widgets compose with layout/blit and layout/split-h/v."
  (:require
   [ai.miniforge.tui-engine.layout :as layout]))

;------------------------------------------------------------------------------ Layer 0
;; Status indicators

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

(defn status-indicator
  "Render a single status indicator character with color.
   Returns a 1-cell buffer."
  [status]
  (let [ch (get status-chars status \?)
        fg (get status-colors status :white)]
    (layout/text [1 1] (str ch) {:fg fg})))

(defn status-text
  "Render a status indicator followed by label text.
   Returns a buffer of [cols 1]."
  [[cols _rows] status label]
  (let [ch (get status-chars status \?)
        fg (get status-colors status :white)
        buf (layout/make-buffer [cols 1])
        buf (layout/buf-put-char buf 0 0 {:char ch :fg fg :bg :black :bold? false})]
    (if (> cols 2)
      (layout/buf-put-string buf 2 0 (subs label 0 (min (count label) (- cols 2)))
                             {:fg :white :bg :black :bold? false})
      buf)))

;------------------------------------------------------------------------------ Layer 1
;; Progress bar

(def ^:private bar-chars
  "Sub-cell progress bar characters (eighths)."
  [\space \▏ \▎ \▍ \▌ \▋ \▊ \▉ \█])

(defn progress-bar
  "Render a progress bar.
   Options:
   - :percent   - 0-100
   - :fill-fg   - Color for filled portion (default :cyan)
   - :empty-fg  - Color for empty portion (default :default)
   - :show-pct? - Show percentage text (default true)"
  [[cols _rows] & [{:keys [percent fill-fg empty-fg show-pct?]
                     :or {percent 0 fill-fg :cyan empty-fg :default show-pct? true}}]]
  (let [pct (max 0 (min 100 percent))
        label (when show-pct? (str " " pct "%"))
        bar-width (max 1 (- cols (if label (count label) 0)))
        filled-cells (/ (* pct bar-width) 100.0)
        full-cells (int filled-cells)
        frac (- filled-cells full-cells)
        frac-idx (int (* frac 8))
        buf (layout/make-buffer [cols 1])
        ;; Fill complete cells
        buf (reduce (fn [b i]
                      (layout/buf-put-char b i 0
                                           {:char \█ :fg fill-fg :bg :black :bold? false}))
                    buf (range (min full-cells bar-width)))
        ;; Fractional cell
        buf (if (and (< full-cells bar-width) (pos? frac-idx))
              (layout/buf-put-char buf full-cells 0
                                   {:char (nth bar-chars frac-idx)
                                    :fg fill-fg :bg :black :bold? false})
              buf)
        ;; Empty cells
        buf (reduce (fn [b i]
                      (layout/buf-put-char b i 0
                                           {:char \░ :fg empty-fg :bg :black :bold? false}))
                    buf
                    (range (if (pos? frac-idx) (inc full-cells) full-cells)
                           bar-width))
        ;; Percentage label
        buf (if label
              (layout/buf-put-string buf bar-width 0 label
                                     {:fg :white :bg :black :bold? false})
              buf)]
    buf))

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
  (let [buf (layout/make-buffer [cols rows])
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
                (layout/buf-put-string b 0 idx text
                                       {:fg (if sel? selected-fg fg)
                                        :bg (if sel? selected-bg :black)
                                        :bold? sel?})))
            buf
            (map-indexed vector visible))))

;------------------------------------------------------------------------------ Layer 3
;; Kanban columns

(defn kanban
  "Render kanban-style columns.
   Options:
   - :columns - [{:title str :color kw :cards [{:label str :status kw}]}]
   Each column gets equal width."
  [[cols rows] & [{:keys [columns]}]]
  (let [n (max 1 (count (or columns [])))
        col-width (max 4 (quot cols n))
        buf (layout/make-buffer [cols rows])]
    (reduce (fn [b [idx {:keys [title color cards]}]]
              (let [x-offset (* idx col-width)
                    avail-w (min col-width (- cols x-offset))
                    ;; Header
                    header (subs (str " " title (apply str (repeat avail-w \space)))
                                 0 (min avail-w (+ (count title) 2)))
                    b (layout/buf-put-string b x-offset 0 header
                                             {:fg (or color :white) :bg :black :bold? true})
                    ;; Separator
                    b (if (>= rows 2)
                        (layout/buf-put-string b x-offset 1
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
                                  (layout/buf-put-string b2 x-offset r line
                                                         {:fg (get status-colors status :white)
                                                          :bg :black :bold? false})))))
                        b
                        (map-indexed vector (or cards [])))))
            buf
            (map-indexed vector (or columns [])))))

;------------------------------------------------------------------------------ Layer 4
;; Scrollable viewport

(defn scrollable
  "Render a scrollable viewport with scrollbar.
   Options:
   - :lines   - vector of strings (total content)
   - :offset  - scroll offset (first visible line index)
   - :fg, :bg - colors"
  [[cols rows] & [{:keys [lines offset fg bg]
                    :or {offset 0 fg :white bg :black}}]]
  (let [total (count (or lines []))
        content-w (max 1 (- cols 1)) ; 1 col for scrollbar
        visible (take rows (drop offset (or lines [])))
        buf (layout/make-buffer [cols rows])
        ;; Render visible lines
        buf (reduce (fn [b [idx line]]
                      (let [truncated (subs line 0 (min (count line) content-w))]
                        (layout/buf-put-string b 0 idx truncated
                                               {:fg fg :bg bg :bold? false})))
                    buf
                    (map-indexed vector visible))
        ;; Scrollbar
        scroll-col (dec cols)
        bar-height (if (pos? total)
                     (max 1 (int (* rows (/ (min rows total) total))))
                     rows)
        bar-top (if (and (pos? total) (> total rows))
                  (int (* (- rows bar-height) (/ offset (- total rows))))
                  0)]
    (reduce (fn [b r]
              (let [in-bar? (and (>= r bar-top) (< r (+ bar-top bar-height)))
                    ch (if in-bar? \▮ \│)]
                (layout/buf-put-char b scroll-col r
                                     {:char ch :fg :default :bg bg :bold? false})))
            buf
            (range rows))))

;------------------------------------------------------------------------------ Layer 5
;; Sparkline

(def ^:private braille-chars
  "Braille characters for sparkline (8 vertical levels)."
  [\⠀ \⡀ \⡄ \⡆ \⡇ \⣇ \⣧ \⣷ \⣿])

(defn sparkline
  "Render a braille-based sparkline.
   Options:
   - :values - vector of numbers
   - :fg     - color (default :cyan)"
  [[cols _rows] & [{:keys [values fg] :or {fg :cyan}}]]
  (let [vals (or values [])
        ;; Take last `cols` values
        visible (if (> (count vals) cols)
                  (subvec vals (- (count vals) cols))
                  vals)
        max-val (if (seq visible) (apply max visible) 1)
        max-val (if (zero? max-val) 1 max-val)
        buf (layout/make-buffer [cols 1])]
    (reduce (fn [b [idx v]]
              (let [level (int (* 8 (/ v max-val)))
                    level (max 0 (min 8 level))
                    ch (nth braille-chars level)]
                (layout/buf-put-char b idx 0
                                     {:char ch :fg fg :bg :black :bold? false})))
            buf
            (map-indexed vector visible))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (layout/buf->strings (progress-bar [20 1] {:percent 40}))
  ;; => ["████████░░░░░░░░ 40%"]

  (layout/buf->strings (status-text [20 1] :running "Generating code"))
  ;; => ["● Generating code   "]

  (layout/buf->strings (sparkline [10 1] {:values [1 3 5 2 8 4 6 9 3 7]}))

  :leave-this-here)
