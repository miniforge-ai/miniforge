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
(ns ai.miniforge.cli.tui
  "Two-pane TUI components for the fleet dashboard.

   Inspired by XTreeGold - provides spatial consistency with:
   - Left pane: Tree navigation (repos, PRs, workflows)
   - Right pane: Detail view of selected item
   - Keyboard-first navigation
   - Information density with progressive disclosure

   The key insight: AI pre-digests content to reduce cognitive load.
   PRs get risk scores, summaries, and suggested actions."
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

;; Risk/complexity scoring
(def ^{:stratum 0} risk-colors
  {:low :green
   :medium :yellow
   :high :red})

(def ^{:stratum 0} risk-icons
  {:low "●"
   :medium "◐"
   :high "◉"})

(defn ^{:stratum 0} analyze-pr-risk
  "Analyze a PR and return risk assessment.

   This is a heuristic-based analysis. In the future, this could
   call an LLM for deeper analysis.

   Returns:
   {:risk :low/:medium/:high
    :complexity :trivial/:simple/:moderate/:complex
    :summary string
    :suggested-action string
    :reasons [string]}"
  [{:keys [title additions deletions changedFiles] :as _pr}]
  (let [;; Size-based heuristics
        total-changes (+ (or additions 0) (or deletions 0))
        file-count (or changedFiles 0)

        ;; Pattern matching on title
        title-lower (str/lower-case (or title ""))
        is-deps? (or (str/includes? title-lower "bump")
                     (str/includes? title-lower "deps")
                     (str/includes? title-lower "dependency"))
        is-docs? (or (str/includes? title-lower "readme")
                     (str/includes? title-lower "docs")
                     (str/includes? title-lower "documentation"))
        is-fix? (str/includes? title-lower "fix")
        is-refactor? (str/includes? title-lower "refactor")
        is-feature? (or (str/includes? title-lower "add")
                        (str/includes? title-lower "feat")
                        (str/includes? title-lower "implement"))

        ;; Calculate risk
        risk (cond
               ;; Low risk patterns
               (and is-docs? (< total-changes 100)) :low
               (and is-deps? (< file-count 3)) :low
               (and (< total-changes 50) (< file-count 3)) :low

               ;; High risk patterns
               (> total-changes 500) :high
               (> file-count 20) :high
               (and is-refactor? (> total-changes 200)) :high

               ;; Medium by default
               :else :medium)

        complexity (cond
                     (< total-changes 20) :trivial
                     (< total-changes 100) :simple
                     (< total-changes 300) :moderate
                     :else :complex)

        ;; Generate summary
        summary (cond
                  is-docs? "Documentation update"
                  is-deps? "Dependency version bump"
                  is-fix? "Bug fix"
                  is-refactor? "Code refactoring"
                  is-feature? "New feature"
                  :else "Code changes")

        suggested-action (case risk
                           :low "✓ Safe to merge"
                           :medium "Review recommended"
                           :high "⚠ Careful review needed")

        reasons (cond-> []
                  (> total-changes 300) (conj (str total-changes " lines changed"))
                  (> file-count 10) (conj (str file-count " files modified"))
                  is-refactor? (conj "Refactoring changes"))]

    {:risk risk
     :complexity complexity
     :summary summary
     :suggested-action suggested-action
     :reasons reasons}))

;; Two-pane layout rendering
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

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} render-box
  "Render a box with title and content lines.
   Uses blue borders for XTreeGold-style visibility.
   Returns vector of strings (one per line)."
  [title lines width height]
  (let [inner-width (- width 2)
        title-str (truncate (or title "") (- inner-width 4))
        ;; Blue borders with bright title
        top-line (str (style "┌─ " :fg :blue) (style title-str :fg :bright-white :bold true)
                      (style " " :fg :blue) (style (repeat-char "─" (- inner-width (count title-str) 3)) :fg :blue)
                      (style "┐" :fg :blue))
        bottom-line (str (style "└" :fg :blue) (style (repeat-char "─" inner-width) :fg :blue) (style "┘" :fg :blue))
        content-height (- height 2)
        padded-lines (take content-height
                           (concat (map #(str (style "│" :fg :blue)
                                              (pad-right (truncate % inner-width) inner-width)
                                              (style "│" :fg :blue))
                                        lines)
                                   (repeat (str (style "│" :fg :blue)
                                                (repeat-char " " inner-width)
                                                (style "│" :fg :blue)))))]
    (vec (concat [top-line] padded-lines [bottom-line]))))

(defn ^{:stratum 2} render-tree-item
  "Render a single tree item with proper indentation and icons.
   Uses XTreeGold-inspired blue highlight for selected items."
  [{:keys [label selected? expanded? has-children? depth risk]} width]
  (let [indent (repeat-char " " (* 2 depth))
        icon (cond
               (and has-children? expanded?) "▼"
               has-children? "▸"
               :else " ")
        risk-indicator (when risk
                         (str (style (get risk-icons risk "○")
                                     :fg (get risk-colors risk :white)) " "))
        prefix (str indent icon " " (or risk-indicator ""))
        label-width (- width (count prefix) 2)
        formatted-label (truncate label label-width)
        line-content (pad-right (str prefix formatted-label) width)]
    (if selected?
      ;; XTreeGold style: bright white on blue background
      (style line-content :fg :bright-white :bg :bg-blue :bold true)
      ;; Normal: bright cyan text for visibility
      (style line-content :fg :bright-cyan))))

;; PR detail rendering
(defn ^{:stratum 2} render-pr-detail
  "Render detailed view of a PR for the right pane."
  [{:keys [number title author state repo] :as _pr} analysis]
  (let [{:keys [risk complexity summary suggested-action reasons]} analysis]
    {:title (str "PR #" number " " (truncate repo 30))
     :sections
     [{:title "OVERVIEW"
       :content [(str "Title: " title)
                 (str "Author: " (get author :login "unknown"))
                 (str "State: " state)
                 (str "Risk: " (style (str/upper-case (name risk))
                                      :fg (get risk-colors risk :white) :bold true)
                      "  Complexity: " (str/upper-case (name complexity)))]}

      {:title "AI SUMMARY"
       :content [summary
                 ""
                 (when (seq reasons)
                   (str "Factors: " (str/join ", " reasons)))]}

      {:title "SUGGESTED ACTION"
       :content [(style suggested-action
                        :fg (case risk :low :green :medium :yellow :red)
                        :bold true)]}

      {:title "QUICK ACTIONS"
       :content ["[a] Approve   [r] Reject   [d] View diff"
                 "[c] Chat      [o] Open in browser"
                 "[j/k] Navigate   [q] Back"]}]}))

;------------------------------------------------------------------------------ Layer 3

(defn ^{:stratum 3} render-two-pane
  "Render a two-pane layout with XTreeGold-inspired color scheme.

   left-pane: {:title string :items [{:label :selected? :expanded? :has-children? :depth :risk}]}
   right-pane: {:title string :sections [{:title string :content [string]}]}
   status-bar: string
   key-hints: string

   Returns string ready to print."
  [{:keys [left-pane right-pane status-bar key-hints]}]
  (let [[term-width term-height] (get-terminal-size)
        left-width (int (* 0.4 term-width))
        right-width (- term-width left-width 1)
        content-height (- term-height 4) ; room for status + hints

        ;; Render left pane items
        left-items (map #(render-tree-item % (- left-width 2)) (:items left-pane))
        left-box (render-box (:title left-pane) left-items left-width content-height)

        ;; Render right pane sections with better visibility
        right-lines (mapcat (fn [{:keys [title content]}]
                              (concat [(style title :fg :bright-yellow :bold true)
                                       (style (repeat-char "─" (- right-width 4)) :fg :blue)]
                                      (map #(style % :fg :bright-white) content)
                                      [""]))
                            (:sections right-pane))
        right-box (render-box (:title right-pane) right-lines right-width content-height)

        ;; Combine horizontally with blue separator
        combined-lines (map (fn [l r] (str l (style "│" :fg :blue) r))
                            left-box
                            right-box)

        ;; Status bar: visible on dark background
        status-line (pad-right (str " " (or status-bar "")) term-width)
        ;; Key hints: bright and visible, NOT dim
        hints-line (pad-right (str " " (or key-hints "")) term-width)]

    (str/join "\n" (concat combined-lines
                           [(style status-line :fg :bright-white :bg :bg-blue)]
                           [(style hints-line :fg :bright-cyan :bold true)]))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Test risk analysis
  (analyze-pr-risk {:title "Bump dependencies"
                    :additions 10
                    :deletions 5
                    :changedFiles 2})

  (analyze-pr-risk {:title "Refactor authentication system"
                    :additions 500
                    :deletions 300
                    :changedFiles 25})

  ;; Test terminal size
  (get-terminal-size)

  ;; Test two-pane rendering
  (println (render-two-pane
            {:left-pane {:title "FLEET PRs"
                         :items [{:label "miniforge-ai/miniforge (3)" :selected? true :expanded? true :has-children? true :depth 0}
                                 {:label "#31 Add Workflow..." :selected? false :depth 1 :risk :low}
                                 {:label "#29 Refactor auth..." :selected? false :depth 1 :risk :medium}]}
             :right-pane {:title "PR #31"
                          :sections [{:title "OVERVIEW" :content ["Title: Add Workflow" "Author: claude"]}
                                     {:title "AI SUMMARY" :content ["Documentation update"]}]}
             :status-bar "3 PRs | 2 safe | 1 needs review"
             :key-hints "[j/k] nav [a]pprove [r]eject [d]iff [c]hat [b]atch-safe [q]uit"}))

  :end)
