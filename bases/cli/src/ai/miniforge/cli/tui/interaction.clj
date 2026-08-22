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
(ns ai.miniforge.cli.tui.interaction
  "Navigation state, GitHub data fetching, and keyboard input for the
   two-pane TUI: the tree-item view models, the flat-list nav state
   machine, `gh` CLI integration, and raw-mode key reading. Extracted
   from `ai.miniforge.cli.tui` (rule 210: the combined namespace
   measured 4 real layers, max 3)."
  (:require
   [ai.miniforge.cli.tui.risk :as risk]
   [ai.miniforge.cli.tui.terminal :as terminal]
   [babashka.process :as process]
   [cheshire.core :as json]))

;------------------------------------------------------------------------------ Layer 0

;; Two-pane layout rendering
(defn ^{:stratum 0} render-repo-item
  "Render a repo as a tree item."
  [repo pr-count expanded? selected?]
  {:label (str repo " (" pr-count ")")
   :selected? selected?
   :expanded? expanded?
   :has-children? (pos? pr-count)
   :depth 0})

;; Interactive navigation state
(defn ^{:stratum 0} create-nav-state
  "Create initial navigation state for the two-pane view."
  [repos-with-prs]
  {:repos repos-with-prs
   :expanded-repos #{}
   :selected-index 0
   :flat-items [] ; computed from repos + expansion state
   :mode :browse})  ; :browse, :detail, :chat

(defn ^{:stratum 0} flatten-nav-items
  "Flatten repos and PRs into a navigable list based on expansion state."
  [{:keys [repos expanded-repos]}]
  (vec (mapcat (fn [{:keys [repo prs]}]
                 (let [expanded? (contains? expanded-repos repo)]
                   (concat [{:type :repo :repo repo :prs prs :expanded? expanded?}]
                           (when expanded?
                             (map #(assoc % :type :pr :repo repo) prs)))))
               repos)))

(defn ^{:stratum 0} nav-up [state]
  (update state :selected-index #(max 0 (dec %))))

(defn ^{:stratum 0} nav-down [state]
  (let [max-idx (dec (count (:flat-items state)))]
    (update state :selected-index #(min max-idx (inc %)))))

(defn ^{:stratum 0} get-selected-item [state]
  (get-in state [:flat-items (:selected-index state)]))

;; GitHub integration
(defn ^{:stratum 0} fetch-pr-details
  "Fetch detailed PR info including additions/deletions."
  [repo number]
  (let [result (process/sh "gh" "pr" "view" (str number)
                           "--repo" repo
                           "--json" "number,title,state,author,url,additions,deletions,changedFiles,body")]
    (when (zero? (:exit result))
      (try
        (json/parse-string (:out result) true)
        (catch Exception _ nil)))))

;; Keyboard input handling
(defn ^{:stratum 0} map-char-to-key
  "Map a character to a key command."
  [c]
  (case c
    \j :down
    \k :up
    \q :quit
    \a :approve
    \r :reject
    \d :diff
    \c :chat
    \o :open
    \b :batch-approve
    \n :next-risky
    \space :toggle
    \return :enter
    \newline :enter
    ;; Default
    c))

(defn ^{:stratum 0} render-pr-list-item
  "Render a PR as a tree item."
  [{:keys [number title]} analysis selected?]
  {:label (str "#" number " " (terminal/truncate title 35))
   :selected? selected?
   :expanded? false
   :has-children? false
   :depth 1
   :risk (:risk analysis)})

(defn ^{:stratum 0} fetch-prs-for-repos
  "Fetch PRs for multiple repos with analysis."
  [repos]
  (vec (for [repo repos]
         (let [result (process/sh "gh" "pr" "list" "--repo" repo
                                  "--json" "number,title,state,author,url,additions,deletions,changedFiles"
                                  "--limit" "20")
               prs (when (zero? (:exit result))
                     (try
                       (json/parse-string (:out result) true)
                       (catch Exception _ [])))]
           {:repo repo
            :prs (vec (for [pr prs]
                        (assoc pr
                               :repo repo
                               :analysis (risk/analyze-pr-risk pr))))}))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} update-flat-items
  "Recompute flat items after state change."
  [state]
  (assoc state :flat-items (flatten-nav-items state)))

(defn ^{:stratum 1} read-key
  "Read a single keypress without requiring Enter.
   Uses stty raw mode with direct /dev/tty access.
   Returns keyword for special keys, char otherwise."
  []
  (try
    ;; Set terminal to raw mode, read one char, restore
    (process/sh "stty" "raw" "-echo" :in (java.io.File. "/dev/tty"))
    (let [tty-stream (java.io.FileInputStream. "/dev/tty")
          char-code (.read tty-stream)]
      (.close tty-stream)
      (process/sh "stty" "cooked" "echo" :in (java.io.File. "/dev/tty"))
      (cond
        ;; EOF or error
        (neg? char-code) :escape
        ;; Escape key (ASCII 27)
        (= char-code 27) :escape
        ;; Map character to command
        :else (map-char-to-key (char char-code))))
    (catch Exception _
      ;; Try to restore terminal on error
      (try (process/sh "stty" "cooked" "echo" :in (java.io.File. "/dev/tty")) (catch Exception _))
      :error)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} toggle-expand [state]
  (let [item (get-in state [:flat-items (:selected-index state)])]
    (if (= :repo (:type item))
      (-> state
          (update :expanded-repos #(if (contains? % (:repo item))
                                     (disj % (:repo item))
                                     (conj % (:repo item))))
          update-flat-items)
      state)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (create-nav-state [{:repo "miniforge-ai/miniforge" :prs []}])

  (map-char-to-key \j)

  :end)
