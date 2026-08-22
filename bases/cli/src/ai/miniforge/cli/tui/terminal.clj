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
(ns ai.miniforge.cli.tui.terminal
  "ANSI/terminal primitives: raw escape-sequence control, color tables,
   ANSI styling, and small string layout helpers. Extracted from
   `ai.miniforge.cli.tui` (rule 210: the combined namespace measured 4
   real layers, max 3)."
  (:require
   [babashka.process :as process]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

;; Terminal utilities
(def ^{:stratum 0} ansi-colors
  "ANSI color codes for foreground colors."
  {:red     "31"
   :green   "32"
   :yellow  "33"
   :blue    "34"
   :magenta "35"
   :cyan    "36"
   :white   "37"
   :gray    "90"
   :bright-red "91"
   :bright-green "92"
   :bright-yellow "93"
   :bright-blue "94"
   :bright-magenta "95"
   :bright-cyan "96"
   :bright-white "97"})

(def ^{:stratum 0} ansi-bg-colors
  "ANSI color codes for background colors."
  {:bg-black "40"
   :bg-red "41"
   :bg-green "42"
   :bg-yellow "43"
   :bg-blue "44"
   :bg-magenta "45"
   :bg-cyan "46"
   :bg-white "47"
   :bg-bright-blue "104"
   :bg-bright-cyan "106"})

(defn ^{:stratum 0} clear-screen []
  (print "\033[2J\033[H")
  (flush))

(defn ^{:stratum 0} move-cursor [row col]
  (print (str "\033[" row ";" col "H"))
  (flush))

(defn ^{:stratum 0} get-terminal-size
  "Get terminal dimensions [width height]."
  []
  (try
    (let [result (process/sh "stty" "size" :in (java.io.FileInputStream. "/dev/tty"))
          [h w] (str/split (str/trim (:out result)) #" ")]
      [(Integer/parseInt w) (Integer/parseInt h)])
    (catch Exception _
      [120 40])))  ; fallback

(defn ^{:stratum 0} hide-cursor []
  (print "\033[?25l")
  (flush))

(defn ^{:stratum 0} show-cursor []
  (print "\033[?25h")
  (flush))

(defn ^{:stratum 0} repeat-char [c n]
  (apply str (repeat n c)))

(defn ^{:stratum 0} truncate [s max-len]
  (if (> (count s) max-len)
    (str (subs s 0 (- max-len 1)) "…")
    s))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} style
  "Apply ANSI styling to text.

   Options:
   - :fg - Foreground color keyword
   - :bg - Background color keyword (e.g. :bg-blue)
   - :bold - Bold text
   - :dim - Dim text
   - :reverse - Reverse video (swap fg/bg)"
  [text & {:keys [fg bg bold dim reverse]}]
  (let [codes (cond-> []
                bold (conj "1")
                dim (conj "2")
                reverse (conj "7")
                fg (conj (get ansi-colors fg "37"))
                bg (conj (get ansi-bg-colors bg)))]
    (if (seq codes)
      (str "\033[" (str/join ";" (remove nil? codes)) "m" text "\033[0m")
      text)))

(defn ^{:stratum 1} pad-right [s width]
  (let [s (or s "")
        len (count s)]
    (if (>= len width)
      (subs s 0 width)
      (str s (repeat-char " " (- width len))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (get-terminal-size)

  (style "hello" :fg :bright-cyan :bold true)

  (pad-right "abc" 6)

  :end)
