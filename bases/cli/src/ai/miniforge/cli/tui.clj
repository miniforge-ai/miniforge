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
   PRs get risk scores, summaries, and suggested actions.

   Layer 0: Box/tree/detail rendering (each only calls
            `ai.miniforge.cli.tui.terminal`/`.risk`, not each other)
   Layer 1: Two-pane composition, orchestrating the layer-0 renderers

   ANSI/terminal primitives, risk heuristics, and nav/keyboard/GitHub
   interaction live in sibling `ai.miniforge.cli.tui.*` namespaces
   (rule 210: the combined namespace measured 4 real layers, max 3).
   Extracting those also shortened this namespace's own in-file call
   chain — the moved code's hops no longer count toward local layer
   depth, so the remaining rendering code now measures 2 real layers
   on its own, within budget."
  (:require
   [ai.miniforge.cli.tui.risk :as risk]
   [ai.miniforge.cli.tui.terminal :as terminal]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} render-box
  "Render a box with title and content lines.
   Uses blue borders for XTreeGold-style visibility.
   Returns vector of strings (one per line)."
  [title lines width height]
  (let [inner-width (- width 2)
        title-str (terminal/truncate (or title "") (- inner-width 4))
        ;; Blue borders with bright title
        top-line (str (terminal/style "┌─ " :fg :blue) (terminal/style title-str :fg :bright-white :bold true)
                      (terminal/style " " :fg :blue) (terminal/style (terminal/repeat-char "─" (- inner-width (count title-str) 3)) :fg :blue)
                      (terminal/style "┐" :fg :blue))
        bottom-line (str (terminal/style "└" :fg :blue) (terminal/style (terminal/repeat-char "─" inner-width) :fg :blue) (terminal/style "┘" :fg :blue))
        content-height (- height 2)
        padded-lines (take content-height
                           (concat (map #(str (terminal/style "│" :fg :blue)
                                              (terminal/pad-right (terminal/truncate % inner-width) inner-width)
                                              (terminal/style "│" :fg :blue))
                                        lines)
                                   (repeat (str (terminal/style "│" :fg :blue)
                                                (terminal/repeat-char " " inner-width)
                                                (terminal/style "│" :fg :blue)))))]
    (vec (concat [top-line] padded-lines [bottom-line]))))

(defn ^{:stratum 0} render-tree-item
  "Render a single tree item with proper indentation and icons.
   Uses XTreeGold-inspired blue highlight for selected items."
  [{:keys [label selected? expanded? has-children? depth risk]} width]
  (let [indent (terminal/repeat-char " " (* 2 depth))
        icon (cond
               (and has-children? expanded?) "▼"
               has-children? "▸"
               :else " ")
        risk-indicator (when risk
                         (str (terminal/style (get risk/risk-icons risk "○")
                                     :fg (get risk/risk-colors risk :white)) " "))
        prefix (str indent icon " " (or risk-indicator ""))
        label-width (- width (count prefix) 2)
        formatted-label (terminal/truncate label label-width)
        line-content (terminal/pad-right (str prefix formatted-label) width)]
    (if selected?
      ;; XTreeGold style: bright white on blue background
      (terminal/style line-content :fg :bright-white :bg :bg-blue :bold true)
      ;; Normal: bright cyan text for visibility
      (terminal/style line-content :fg :bright-cyan))))

;; PR detail rendering
(defn ^{:stratum 0} render-pr-detail
  "Render detailed view of a PR for the right pane."
  [{:keys [number title author state repo] :as _pr} analysis]
  (let [{:keys [risk complexity summary suggested-action reasons]} analysis]
    {:title (str "PR #" number " " (terminal/truncate repo 30))
     :sections
     [{:title "OVERVIEW"
       :content [(str "Title: " title)
                 (str "Author: " (get author :login "unknown"))
                 (str "State: " state)
                 (str "Risk: " (terminal/style (str/upper-case (name risk))
                                      :fg (get risk/risk-colors risk :white) :bold true)
                      "  Complexity: " (str/upper-case (name complexity)))]}

      {:title "AI SUMMARY"
       :content [summary
                 ""
                 (when (seq reasons)
                   (str "Factors: " (str/join ", " reasons)))]}

      {:title "SUGGESTED ACTION"
       :content [(terminal/style suggested-action
                        :fg (case risk :low :green :medium :yellow :red)
                        :bold true)]}

      {:title "QUICK ACTIONS"
       :content ["[a] Approve   [r] Reject   [d] View diff"
                 "[c] Chat      [o] Open in browser"
                 "[j/k] Navigate   [q] Back"]}]}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} render-two-pane
  "Render a two-pane layout with XTreeGold-inspired color scheme.

   left-pane: {:title string :items [{:label :selected? :expanded? :has-children? :depth :risk}]}
   right-pane: {:title string :sections [{:title string :content [string]}]}
   status-bar: string
   key-hints: string

   Returns string ready to print."
  [{:keys [left-pane right-pane status-bar key-hints]}]
  (let [[term-width term-height] (terminal/get-terminal-size)
        left-width (int (* 0.4 term-width))
        right-width (- term-width left-width 1)
        content-height (- term-height 4) ; room for status + hints

        ;; Render left pane items
        left-items (map #(render-tree-item % (- left-width 2)) (:items left-pane))
        left-box (render-box (:title left-pane) left-items left-width content-height)

        ;; Render right pane sections with better visibility
        right-lines (mapcat (fn [{:keys [title content]}]
                              (concat [(terminal/style title :fg :bright-yellow :bold true)
                                       (terminal/style (terminal/repeat-char "─" (- right-width 4)) :fg :blue)]
                                      (map #(terminal/style % :fg :bright-white) content)
                                      [""]))
                            (:sections right-pane))
        right-box (render-box (:title right-pane) right-lines right-width content-height)

        ;; Combine horizontally with blue separator
        combined-lines (map (fn [l r] (str l (terminal/style "│" :fg :blue) r))
                            left-box
                            right-box)

        ;; Status bar: visible on dark background
        status-line (terminal/pad-right (str " " (or status-bar "")) term-width)
        ;; Key hints: bright and visible, NOT dim
        hints-line (terminal/pad-right (str " " (or key-hints "")) term-width)]

    (str/join "\n" (concat combined-lines
                           [(terminal/style status-line :fg :bright-white :bg :bg-blue)]
                           [(terminal/style hints-line :fg :bright-cyan :bold true)]))))

;------------------------------------------------------------------------------ Rich Comment
(comment
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
