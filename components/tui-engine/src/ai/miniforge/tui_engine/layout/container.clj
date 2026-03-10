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

(ns ai.miniforge.tui-engine.layout.container
  "Box drawing and split layout primitives.

   Provides bordered boxes and horizontal/vertical layout splits.
   Pure functions with no side effects."
  (:require
   [ai.miniforge.tui-engine.layout.buffer :as buf]))

;------------------------------------------------------------------------------ Layer 2
;; Box drawing

(def box-chars
  {:single {:tl \┌ :tr \┐ :bl \└ :br \┘ :h \─ :v \│ :lt \├ :rt \┤ :tt \┬ :bt \┴}
   :double {:tl \╔ :tr \╗ :bl \╚ :br \╝ :h \═ :v \║ :lt \╠ :rt \╣ :tt \╦ :bt \╩}
   :none   {:tl \space :tr \space :bl \space :br \space
            :h \space :v \space :lt \space :rt \space :tt \space :bt \space}})

(defn truncate-str
  "Truncate string to max-width, adding ellipsis if truncated."
  [s max-width]
  (if (<= (count s) max-width)
    s
    (str (subs s 0 (max 0 (- max-width 1))) "…")))

(defn box
  "Render a bordered box.
   Options:
   - :border - :single (default), :double, or :none
   - :title  - Optional string for top border
   - :fg, :bg, :bold? - Border style
   - :content-fn - (fn [[inner-cols inner-rows]] -> cell-buffer) for box contents"
  [[cols rows] & [{:keys [border title fg bg bold? content-fn]
                    :or {border :single fg :default bg :default bold? false}}]]
  (when (and (>= cols 2) (>= rows 2))
    (let [chars (get box-chars border (:single box-chars))
          style {:fg fg :bg bg :bold? bold?}
          buffer (buf/make-buffer [cols rows])
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
          buffer (buf/buf-put-string buffer 0 0 top-line style)
          buffer (buf/buf-put-string buffer 0 (dec rows) bot-line style)
          ;; Side borders
          buffer (reduce (fn [b r]
                        (-> b
                            (buf/buf-put-char 0 r (assoc style :char (:v chars)))
                            (buf/buf-put-char (dec cols) r (assoc style :char (:v chars)))))
                      buffer
                      (range 1 (dec rows)))]
      (if content-fn
        (let [inner-cols (- cols 2)
              inner-rows (- rows 2)
              content (content-fn [inner-cols inner-rows])]
          (buf/blit buffer content 1 1))
        buffer))))

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
        buffer (buf/make-buffer [cols rows])]
    (-> buffer
        (buf/blit left-buf 0 0)
        (buf/blit right-buf left-cols 0))))

(defn split-v
  "Split vertically into top/bottom panes.
   ratio is the fraction [0.0, 1.0] for the top pane.
   top-fn, bottom-fn: (fn [[cols rows]] -> cell-buffer)"
  [[cols rows] ratio top-fn bottom-fn]
  (let [top-rows (max 1 (int (* rows ratio)))
        bottom-rows (- rows top-rows)
        top-buf (top-fn [cols top-rows])
        bottom-buf (bottom-fn [cols bottom-rows])
        buffer (buf/make-buffer [cols rows])]
    (-> buffer
        (buf/blit top-buf 0 0)
        (buf/blit bottom-buf 0 top-rows))))
