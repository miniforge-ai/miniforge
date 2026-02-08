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

(ns ai.miniforge.tui-engine.widget.scroll
  "Scrollable viewport and sparkline widgets.

   Provides scrollable content views and mini data visualizations.
   Pure functions with no side effects."
  (:require
   [ai.miniforge.tui-engine.layout.buffer :as buf]))

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
        buffer (buf/make-buffer [cols rows])
        ;; Render visible lines
        buffer (reduce (fn [b [idx line]]
                      (let [truncated (subs line 0 (min (count line) content-w))]
                        (buf/buf-put-string b 0 idx truncated
                                               {:fg fg :bg bg :bold? false})))
                    buffer
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
                (buf/buf-put-char b scroll-col r
                                     {:char ch :fg :default :bg bg :bold? false})))
            buffer
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
        buffer (buf/make-buffer [cols 1])]
    (reduce (fn [b [idx v]]
              (let [level (int (* 8 (/ v max-val)))
                    level (max 0 (min 8 level))
                    ch (nth braille-chars level)]
                (buf/buf-put-char b idx 0
                                     {:char ch :fg fg :bg :black :bold? false})))
            buffer
            (map-indexed vector visible))))
