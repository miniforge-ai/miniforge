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

(ns ai.miniforge.tui-engine.layout.table
  "Table rendering and padding utilities.

   Provides data table rendering with column specs and content padding.
   Pure functions with no side effects."
  (:require
   [ai.miniforge.tui-engine.layout.buffer :as buf]))

;------------------------------------------------------------------------------ Layer 4
;; Table

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

(defn- compute-col-widths
  "Compute column widths from specs. Fixed :width honored, remainder distributed."
  [col-specs total-width]
  (let [fixed-used (reduce + 0 (keep :width col-specs))
        flex-count (count (remove :width col-specs))
        flex-each (if (pos? flex-count)
                    (max 1 (quot (- total-width fixed-used) flex-count))
                    0)]
    (mapv (fn [spec] (or (:width spec) flex-each)) col-specs)))

(defn- compute-col-offset
  "Compute x-offset for column at index."
  [col-widths idx]
  (reduce + 0 (take idx col-widths)))

(defn- render-header-row
  "Render table header row."
  [buffer cols columns col-widths header-fg header-bg]
  (reduce (fn [b [idx {:keys [header]} width]]
            (let [txt (pad-right (truncate-str (or header "") width) width)
                  x-offset (compute-col-offset col-widths idx)]
              (buf/buf-put-string b x-offset 0 txt
                                  {:fg header-fg :bg header-bg :bold? true})))
          buffer
          (map vector (range) columns col-widths)))

(defn- render-separator
  "Render horizontal separator line."
  [buffer cols rows]
  (if (>= rows 2)
    (buf/buf-put-string buffer 0 1
                        (apply str (repeat cols \─))
                        {:fg :default :bg :black :bold? false})
    buffer))

(defn- render-data-cell
  "Render single data cell."
  [buffer row-data col-idx col-spec width col-widths render-row fg bg]
  (let [val (str (get row-data (:key col-spec) ""))
        txt (pad-right (truncate-str val width) width)
        x-offset (compute-col-offset col-widths col-idx)]
    (buf/buf-put-string buffer x-offset render-row txt
                        {:fg fg :bg bg :bold? false})))

(defn- render-data-row
  "Render single data row across all columns."
  [buffer row-idx row-data columns col-widths rows selected-row row-fg row-bg selected-fg selected-bg]
  (let [selected? (= row-idx selected-row)
        fg (if selected? selected-fg row-fg)
        bg (if selected? selected-bg row-bg)
        render-row (+ row-idx 2)]
    (if (>= render-row rows)
      (reduced buffer)
      (reduce (fn [b [col-idx col-spec width]]
                (render-data-cell b row-data col-idx col-spec width col-widths render-row fg bg))
              buffer
              (map vector (range) columns col-widths)))))

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
        buffer (buf/make-buffer [cols rows])
        buffer (render-header-row buffer cols columns col-widths header-fg header-bg)
        buffer (render-separator buffer cols rows)
        visible-rows (take (max 0 (- rows 2)) (or data []))]
    (reduce (fn [b [row-idx row-data]]
              (render-data-row b row-idx row-data columns col-widths rows
                               selected-row row-fg row-bg selected-fg selected-bg))
            buffer
            (map-indexed vector visible-rows))))

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
        buffer (buf/make-buffer [cols rows])]
    (buf/blit buffer inner-buf pl pt)))
