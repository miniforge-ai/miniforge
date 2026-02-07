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

(ns ai.miniforge.tui-engine.layout
  "Layout primitives for TUI rendering.

   Each function takes a size [cols rows] and options, returning a cell buffer --
   a 2D vector of {:char :fg :bg :bold?} maps. These buffers compose via
   split-h/split-v to build full screen layouts.

   Layout functions are pure -- they take data and return data. No side effects."
  (:require
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Cell buffer operations

(def empty-cell {:char \space :fg :white :bg :black :bold? false})

(defn make-buffer
  "Create an empty cell buffer of [cols rows]."
  [[cols rows]]
  (vec (repeat rows (vec (repeat cols empty-cell)))))

(defn buf-put-char
  "Put a single character at [col row] in buffer."
  [buf col row cell]
  (if (and (< row (count buf))
           (>= row 0)
           (< col (count (first buf)))
           (>= col 0))
    (assoc-in buf [row col] cell)
    buf))

(defn buf-put-string
  "Write a string into the buffer at [col row] with given style."
  [buf col row text {:keys [fg bg bold?] :or {fg :white bg :black bold? false}}]
  (reduce (fn [b i]
            (if (< (+ col i) (count (first b)))
              (buf-put-char b (+ col i) row
                            {:char (.charAt text i) :fg fg :bg bg :bold? bold?})
              b))
          buf
          (range (count text))))

(defn blit
  "Copy source buffer onto dest buffer at [col row] offset."
  [dest src col row]
  (reduce (fn [d r]
            (reduce (fn [d2 c]
                      (let [cell (get-in src [r c])]
                        (if (and cell
                                 (< (+ row r) (count d2))
                                 (< (+ col c) (count (first d2))))
                          (assoc-in d2 [(+ row r) (+ col c)] cell)
                          d2)))
                    d
                    (range (count (first src)))))
          dest
          (range (count src))))

(defn buf->strings
  "Convert a cell buffer to a vector of plain strings (for testing)."
  [buf]
  (mapv (fn [row] (apply str (map :char row))) buf))

;------------------------------------------------------------------------------ Layer 1
;; Text primitives

(defn- truncate-str
  "Truncate string to max-width, adding ellipsis if truncated."
  [s max-width]
  (if (<= (count s) max-width)
    s
    (str (subs s 0 (max 0 (- max-width 1))) "…")))

(defn- pad-right
  "Pad string to width with spaces."
  [s width]
  (let [s (or s "")]
    (if (>= (count s) width)
      (subs s 0 width)
      (str s (apply str (repeat (- width (count s)) \space))))))

(defn text
  "Render a styled text span into a buffer.
   Truncates to fit within [cols rows]."
  [[cols rows] content & [{:keys [fg bg bold? align]
                            :or {fg :white bg :black bold? false align :left}}]]
  (let [buf (make-buffer [cols rows])
        lines (str/split-lines (or content ""))
        lines (take rows lines)]
    (reduce (fn [b [idx line]]
              (let [truncated (truncate-str line cols)
                    padded (case align
                             :right (str (apply str (repeat (max 0 (- cols (count truncated))) \space))
                                         truncated)
                             :center (let [pad (max 0 (quot (- cols (count truncated)) 2))]
                                       (str (apply str (repeat pad \space)) truncated))
                             (pad-right truncated cols))]
                (buf-put-string b 0 idx padded {:fg fg :bg bg :bold? bold?})))
            buf
            (map-indexed vector lines))))

;------------------------------------------------------------------------------ Layer 2
;; Box drawing

(def ^:private box-chars
  {:single {:tl \┌ :tr \┐ :bl \└ :br \┘ :h \─ :v \│ :lt \├ :rt \┤ :tt \┬ :bt \┴}
   :double {:tl \╔ :tr \╗ :bl \╚ :br \╝ :h \═ :v \║ :lt \╠ :rt \╣ :tt \╦ :bt \╩}
   :none   {:tl \space :tr \space :bl \space :br \space
            :h \space :v \space :lt \space :rt \space :tt \space :bt \space}})

(defn box
  "Render a bordered box.
   Options:
   - :border - :single (default), :double, or :none
   - :title  - Optional string for top border
   - :fg, :bg, :bold? - Border style
   - :content-fn - (fn [[inner-cols inner-rows]] -> cell-buffer) for box contents"
  [[cols rows] & [{:keys [border title fg bg bold? content-fn]
                    :or {border :single fg :default bg :black bold? false}}]]
  (when (and (>= cols 2) (>= rows 2))
    (let [chars (get box-chars border (:single box-chars))
          style {:fg fg :bg bg :bold? bold?}
          buf (make-buffer [cols rows])
          ;; Top border
          top-line (str (:tl chars)
                        (if title
                          (let [t (truncate-str title (- cols 4))
                                remaining (- cols 2 (count t) 2)]
                            (str (:h chars) t (apply str (repeat (max 0 (+ remaining 1)) (:h chars)))))
                          (apply str (repeat (- cols 2) (:h chars))))
                        (:tr chars))
          ;; Bottom border
          bot-line (str (:bl chars) (apply str (repeat (- cols 2) (:h chars))) (:br chars))
          buf (buf-put-string buf 0 0 top-line style)
          buf (buf-put-string buf 0 (dec rows) bot-line style)
          ;; Side borders
          buf (reduce (fn [b r]
                        (-> b
                            (buf-put-char 0 r (assoc style :char (:v chars)))
                            (buf-put-char (dec cols) r (assoc style :char (:v chars)))))
                      buf
                      (range 1 (dec rows)))]
      (if content-fn
        (let [inner-cols (- cols 2)
              inner-rows (- rows 2)
              content (content-fn [inner-cols inner-rows])]
          (blit buf content 1 1))
        buf))))

;------------------------------------------------------------------------------ Layer 3
;; Split layouts

(defn split-h
  "Split horizontally into left/right panes.
   ratio is the fraction [0.0, 1.0] for the left pane.
   left-fn, right-fn: (fn [[cols rows]] -> cell-buffer)"
  [[cols rows] ratio left-fn right-fn]
  (let [left-cols (max 1 (int (* cols ratio)))
        right-cols (- cols left-cols)
        left-buf (left-fn [left-cols rows])
        right-buf (right-fn [right-cols rows])
        buf (make-buffer [cols rows])]
    (-> buf
        (blit left-buf 0 0)
        (blit right-buf left-cols 0))))

(defn split-v
  "Split vertically into top/bottom panes.
   ratio is the fraction [0.0, 1.0] for the top pane.
   top-fn, bottom-fn: (fn [[cols rows]] -> cell-buffer)"
  [[cols rows] ratio top-fn bottom-fn]
  (let [top-rows (max 1 (int (* rows ratio)))
        bottom-rows (- rows top-rows)
        top-buf (top-fn [cols top-rows])
        bottom-buf (bottom-fn [cols bottom-rows])
        buf (make-buffer [cols rows])]
    (-> buf
        (blit top-buf 0 0)
        (blit bottom-buf 0 top-rows))))

;------------------------------------------------------------------------------ Layer 4
;; Table

(defn- compute-col-widths
  "Compute column widths from specs. Fixed :width honored, remainder distributed."
  [col-specs total-width]
  (let [fixed-used (reduce + 0 (keep :width col-specs))
        flex-count (count (remove :width col-specs))
        flex-each (if (pos? flex-count)
                    (max 1 (quot (- total-width fixed-used) flex-count))
                    0)]
    (mapv (fn [spec] (or (:width spec) flex-each)) col-specs)))

(defn table
  "Render a table with column headers and data rows.
   Options:
   - :columns - vector of {:key kw, :header str, :width int (opt), :align :left/:right/:center}
   - :rows    - vector of maps keyed by column :key
   - :selected-row - index of highlighted row (nil for none)
   - :header-fg, :header-bg - Header style
   - :row-fg, :row-bg - Default row style
   - :selected-fg, :selected-bg - Selected row style"
  [[cols rows] & [{:keys [columns data selected-row
                           header-fg header-bg row-fg row-bg selected-fg selected-bg]
                    :or {header-fg :cyan header-bg :black
                         row-fg :white row-bg :black
                         selected-fg :white selected-bg :blue}}]]
  (let [col-widths (compute-col-widths columns cols)
        buf (make-buffer [cols rows])
        ;; Render header
        buf (reduce (fn [b [idx {:keys [header]} width]]
                      (let [txt (pad-right (truncate-str (or header "") width) width)]
                        (buf-put-string b
                                        (reduce + 0 (take idx col-widths))
                                        0 txt
                                        {:fg header-fg :bg header-bg :bold? true})))
                    buf
                    (map vector (range) columns col-widths))
        ;; Render separator
        buf (if (>= rows 2)
              (buf-put-string buf 0 1
                              (apply str (repeat cols \─))
                              {:fg :default :bg :black :bold? false})
              buf)
        ;; Render data rows
        visible-rows (take (max 0 (- rows 2)) (or data []))
        buf (reduce (fn [b [row-idx row-data]]
                      (let [selected? (= row-idx selected-row)
                            fg (if selected? selected-fg row-fg)
                            bg (if selected? selected-bg row-bg)
                            render-row (+ row-idx 2)]
                        (if (>= render-row rows)
                          (reduced b)
                          (reduce (fn [b2 [col-idx {:keys [key]} width]]
                                    (let [val (str (get row-data key ""))
                                          txt (pad-right (truncate-str val width) width)]
                                      (buf-put-string b2
                                                      (reduce + 0 (take col-idx col-widths))
                                                      render-row txt
                                                      {:fg fg :bg bg :bold? false})))
                                  b
                                  (map vector (range) columns col-widths)))))
                    buf
                    (map-indexed vector visible-rows))]
    buf))

;------------------------------------------------------------------------------ Layer 5
;; Padding

(defn pad
  "Add padding around a content-fn's output.
   padding: [top right bottom left] or single int for all sides."
  [[cols rows] padding content-fn]
  (let [[pt pr pb pl] (if (vector? padding)
                         padding
                         [padding padding padding padding])
        inner-cols (max 0 (- cols pl pr))
        inner-rows (max 0 (- rows pt pb))
        inner-buf (content-fn [inner-cols inner-rows])
        buf (make-buffer [cols rows])]
    (blit buf inner-buf pl pt)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Quick layout test
  (buf->strings (text [20 1] "Hello, world!"))
  ;; => ["Hello, world!       "]

  (buf->strings (box [20 5] {:title "Test" :border :single}))
  ;; => ["┌─Test──────────────┐"
  ;;     "│                  │"
  ;;     "│                  │"
  ;;     "│                  │"
  ;;     "└──────────────────┘"]

  :leave-this-here)
