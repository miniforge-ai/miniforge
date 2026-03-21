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

(ns ai.miniforge.tui-engine.layout.buffer
  "Cell buffer operations and text primitives for TUI rendering.

   Provides low-level cell buffer manipulation and basic text rendering.
   Pure functions with no side effects."
  (:require
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Cell buffer operations

(def empty-cell {:char \space :fg :default :bg :default :bold? false})

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
  "Write a string into the buffer at [col row] with given style.
   Uses transient inner vector for batch character writes."
  [buf col row text {:keys [fg bg bold?] :or {fg :default bg :default bold? false}}]
  (if (or (neg? row) (>= row (count buf)))
    buf
    (let [row-vec (nth buf row)
          row-width (count row-vec)
          text-len (count text)
          end (min text-len (- row-width col))]
      (if (<= end 0)
        buf
        (let [t-row (transient row-vec)]
          (dotimes [i end]
            (assoc! t-row (+ col i)
                    {:char (.charAt ^String text i) :fg fg :bg bg :bold? bold?}))
          (assoc buf row (persistent! t-row)))))))

(defn blit
  "Copy source buffer onto dest buffer at [col row] offset.
   Uses transient inner vectors for batch row copying."
  [dest src col row]
  (let [dest-rows (count dest)
        dest-cols (if (pos? dest-rows) (count (nth dest 0)) 0)
        src-rows (count src)
        src-cols (if (pos? src-rows) (count (nth src 0)) 0)]
    (loop [d dest r 0]
      (if (>= r src-rows)
        d
        (let [dst-row (+ row r)]
          (if (>= dst-row dest-rows)
            d
            (let [src-row-vec (nth src r)
                  dst-row-vec (nth d dst-row)
                  copy-end (min src-cols (- dest-cols col))
                  t-row (transient dst-row-vec)]
              (dotimes [c copy-end]
                (let [cell (nth src-row-vec c)]
                  (when cell
                    (assoc! t-row (+ col c) cell))))
              (recur (assoc d dst-row (persistent! t-row))
                     (inc r)))))))))

(defn buf->strings
  "Convert a cell buffer to a vector of plain strings (for testing)."
  [buf]
  (mapv (fn [row] (apply str (map :char row))) buf))

;------------------------------------------------------------------------------ Layer 1
;; Text primitives

(defn truncate-str
  "Truncate string to max-width, adding ellipsis if truncated."
  [s max-width]
  (if (<= (count s) max-width)
    s
    (str (subs s 0 (max 0 (- max-width 1))) "…")))

(defn pad-right
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
                            :or {fg :default bg :default bold? false align :left}}]]
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
