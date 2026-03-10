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

(ns ai.miniforge.tui-engine.widget.status
  "Status indicators and progress bar widgets.

   Provides visual indicators for status states and progress visualization.
   Pure functions with no side effects."
  (:require
   [ai.miniforge.tui-engine.layout.buffer :as buf]))

;------------------------------------------------------------------------------ Layer 0
;; Status indicators

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

(defn status-indicator
  "Render a single status indicator character with color.
   Returns a 1-cell buffer."
  [status]
  (let [ch (get status-chars status \?)
        fg (get status-colors status :white)]
    (buf/text [1 1] (str ch) {:fg fg})))

(defn status-text
  "Render a status indicator followed by label text.
   Returns a buffer of [cols 1]."
  [[cols _rows] status label]
  (let [ch (get status-chars status \?)
        fg (get status-colors status :white)
        buffer (buf/make-buffer [cols 1])
        buffer (buf/buf-put-char buffer 0 0 {:char ch :fg fg :bg :default :bold? false})]
    (if (> cols 2)
      (buf/buf-put-string buffer 2 0 (subs label 0 (min (count label) (- cols 2)))
                             {:fg :default :bg :default :bold? false})
      buffer)))

;------------------------------------------------------------------------------ Layer 1
;; Progress bar

(def bar-chars
  "Sub-cell progress bar characters (eighths)."
  [\space \▏ \▎ \▍ \▌ \▋ \▊ \▉ \█])

(defn render-filled-cells
  "Render complete filled cells."
  [buffer full-cells bar-width fill-fg]
  (reduce (fn [b i]
            (buf/buf-put-char b i 0
                              {:char \█ :fg fill-fg :bg :default :bold? false}))
          buffer
          (range (min full-cells bar-width))))

(defn render-fractional-cell
  "Render partial fill for fractional progress."
  [buffer full-cells bar-width frac-idx fill-fg]
  (if (and (< full-cells bar-width) (pos? frac-idx))
    (buf/buf-put-char buffer full-cells 0
                      {:char (nth bar-chars frac-idx)
                       :fg fill-fg :bg :default :bold? false})
    buffer))

(defn render-empty-cells
  "Render remaining empty cells."
  [buffer full-cells frac-idx bar-width empty-fg]
  (reduce (fn [b i]
            (buf/buf-put-char b i 0
                              {:char \░ :fg empty-fg :bg :default :bold? false}))
          buffer
          (range (if (pos? frac-idx) (inc full-cells) full-cells) bar-width)))

(defn add-percentage-label
  "Add percentage text label at end of bar."
  [buffer bar-width label]
  (if label
    (buf/buf-put-string buffer bar-width 0 label
                        {:fg :default :bg :default :bold? false})
    buffer))

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
        frac-idx (int (* frac 8))]
    (-> (buf/make-buffer [cols 1])
        (render-filled-cells full-cells bar-width fill-fg)
        (render-fractional-cell full-cells bar-width frac-idx fill-fg)
        (render-empty-cells full-cells frac-idx bar-width empty-fg)
        (add-percentage-label bar-width label))))
