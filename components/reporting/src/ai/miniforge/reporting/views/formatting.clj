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

(ns ai.miniforge.reporting.views.formatting
  "Low-level formatting utilities for terminal output.

   Provides ANSI color codes, box drawing, and table formatting."
  (:require [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; ANSI color codes and basic colorization

(def ansi-codes
  "ANSI escape codes for terminal colors and styles."
  {:reset "\033[0m"
   :bold "\033[1m"
   :black "\033[30m"
   :red "\033[31m"
   :green "\033[32m"
   :yellow "\033[33m"
   :blue "\033[34m"
   :magenta "\033[35m"
   :cyan "\033[36m"
   :white "\033[37m"
   :bg-black "\033[40m"
   :bg-red "\033[41m"
   :bg-green "\033[42m"
   :bg-yellow "\033[43m"
   :bg-blue "\033[44m"})

(defn ansi
  "Apply ANSI color/formatting to text."
  [code text]
  (str (get ansi-codes code "") text (:reset ansi-codes)))

(defn status-color
  "Get color for a status keyword."
  [status]
  (case status
    (:completed :running :active :ok) :green
    (:pending :idle) :yellow
    (:failed :error) :red
    :white))

;------------------------------------------------------------------------------ Layer 1
;; Box drawing and separators

(def box-chars
  "Unicode box drawing characters."
  {:horizontal "─"
   :vertical "│"
   :top-left "┌"
   :top-right "┐"
   :bottom-left "└"
   :bottom-right "┘"
   :cross "┼"
   :t-down "┬"
   :t-up "┴"
   :t-right "├"
   :t-left "┤"})

(defn draw-box
  "Draw a box around content."
  [title content width]
  (let [title-str (str " " title " ")
        title-len (count title-str)
        padding (max 0 (- width title-len 2))
        top-line (str (:top-left box-chars)
                      title-str
                      (apply str (repeat padding (:horizontal box-chars)))
                      (:top-right box-chars))
        bottom-line (str (:bottom-left box-chars)
                         (apply str (repeat (- width 2) (:horizontal box-chars)))
                         (:bottom-right box-chars))
        content-lines (str/split-lines content)]
    (str/join "\n"
              (concat
               [top-line]
               (map (fn [line]
                      (let [line-len (count line)
                            pad (max 0 (- width line-len 2))]
                        (str (:vertical box-chars)
                             line
                             (apply str (repeat pad " "))
                             (:vertical box-chars))))
                    content-lines)
               [bottom-line]))))

(defn draw-separator
  "Draw a horizontal separator."
  [width]
  (apply str (repeat width (:horizontal box-chars))))

;------------------------------------------------------------------------------ Layer 2
;; Table formatting

(defn format-table
  "Format data as a table."
  [headers rows]
  (let [col-count (count headers)
        col-widths (map (fn [idx]
                          (apply max
                                 (count (nth headers idx))
                                 (map #(count (str (nth % idx ""))) rows)))
                        (range col-count))
        format-row (fn [row]
                     (str/join " │ "
                               (map-indexed
                                (fn [idx val]
                                  (let [width (nth col-widths idx)
                                        val-str (str val)]
                                    (str val-str
                                         (apply str (repeat (- width (count val-str)) " ")))))
                                row)))
        header-line (format-row headers)
        separator (str/join "─┼─"
                            (map #(apply str (repeat % "─")) col-widths))]
    (str/join "\n"
              (concat
               [header-line separator]
               (map format-row rows)))))

(comment
  ;; Test ANSI colors
  (println (ansi :red "Error message"))
  (println (ansi :green "Success message"))
  (println (ansi (status-color :running) "Running"))

  ;; Test box drawing
  (println (draw-box "Title" "Line 1\nLine 2\nLine 3" 40))
  (println (draw-separator 40))

  ;; Test table formatting
  (println (format-table
            ["Name" "Status" "Count"]
            [["Task 1" "Running" "42"]
             ["Task 2" "Pending" "8"]]))

  :end)
