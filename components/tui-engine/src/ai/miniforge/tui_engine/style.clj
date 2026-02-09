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

(ns ai.miniforge.tui-engine.style
  "Theme data and color resolution for TUI rendering.

   Provides a theme system with ANSI-256 color fallback.
   Auto-detects terminal capabilities via $TERM_PROGRAM.

   Themes are pure data maps -- no side effects.")

;; ─────────────────────────────────────────────────────────────────────────────
;; Terminal detection

(defn detect-terminal
  "Detect terminal type from environment.
   Returns :iterm2, :terminal-app, :tmux, :screen, or :unknown."
  []
  (let [term-program (System/getenv "TERM_PROGRAM")
        term         (System/getenv "TERM")]
    (cond
      (= term-program "iTerm.app")  :iterm2
      (= term-program "Apple_Terminal") :terminal-app
      (some? (System/getenv "TMUX")) :tmux
      (= term "screen")             :screen
      :else                          :unknown)))

(defn color-depth
  "Return color depth for terminal: :true-color, :256, or :16."
  []
  (let [colorterm (System/getenv "COLORTERM")
        terminal  (detect-terminal)]
    (cond
      (or (= colorterm "truecolor")
          (= colorterm "24bit")
          (= terminal :iterm2))       :true-color
      (= terminal :terminal-app)      :256
      :else                            :256)))

;; ─────────────────────────────────────────────────────────────────────────────
;; ANSI named colors -> Lanterna color keywords

(def named-colors
  "ANSI named color palette."
  {:black   :black
   :red     :red
   :green   :green
   :yellow  :yellow
   :blue    :blue
   :magenta :magenta
   :cyan    :cyan
   :white   :white
   :default :default})

;; ─────────────────────────────────────────────────────────────────────────────
;; Theme definition

(def default-theme
  "Default dark theme for miniforge TUI."
  {;; Base colors
   :bg              :black
   :fg              :white
   :fg-dim          :default

   ;; Status indicators
   :status/running  :cyan
   :status/success  :green
   :status/failed   :red
   :status/blocked  :yellow
   :status/pending  :default
   :status/skipped  :default

   ;; UI chrome
   :border          :default
   :border-focus    :cyan
   :title           :white
   :title-focus     :cyan
   :header          :cyan
   :selected-bg     :blue
   :selected-fg     :white

   ;; Progress
   :progress-fill   :cyan
   :progress-empty  :default

   ;; Kanban columns
   :kanban/blocked  :red
   :kanban/pending  :yellow
   :kanban/running  :cyan
   :kanban/done     :green

   ;; Sparkline
   :sparkline       :cyan

   ;; Command / search bar
   :command-bg      :default
   :command-fg      :white
   :search-match    :yellow})

(defn resolve-color
  "Resolve a theme key to a Lanterna-compatible color keyword.
   Falls back to :default if not found."
  [theme key]
  (get theme key :default))

(defn resolve-style
  "Resolve a style map {:fg theme-key :bg theme-key :bold? bool} against a theme.
   Returns {:fg color :bg color :bold? bool}."
  [theme {:keys [fg bg bold?] :or {fg :fg bg :bg bold? false}}]
  {:fg     (resolve-color theme fg)
   :bg     (resolve-color theme bg)
   :bold?  bold?})
