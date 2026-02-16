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

   Provides a theme system supporting ANSI-16, ANSI-256, and true-color.
   Auto-detects terminal capabilities via $TERM_PROGRAM / $COLORTERM.

   Color values in themes can be:
   - Keywords  (:cyan, :red, etc.)  — 8 standard ANSI colors
   - Integers  (0–255)              — ANSI-256 indexed colors
   - Vectors   [r g b]              — 24-bit true-color RGB

   Themes are pure data maps — no side effects.")

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
;; ANSI named colors

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
;; Color degradation — map extended colors to lower color depths

(def ^:private ansi-reference
  "ANSI 16 base colors with approximate RGB values for nearest-match."
  [[:black   [0 0 0]]
   [:red     [205 0 0]]
   [:green   [0 205 0]]
   [:yellow  [205 205 0]]
   [:blue    [0 0 238]]
   [:magenta [205 0 205]]
   [:cyan    [0 205 205]]
   [:white   [229 229 229]]])

(defn- rgb-distance-sq
  "Squared Euclidean distance between two [r g b] triples."
  [[r1 g1 b1] [r2 g2 b2]]
  (+ (* (- r1 r2) (- r1 r2))
     (* (- g1 g2) (- g1 g2))
     (* (- b1 b2) (- b1 b2))))

(defn- nearest-ansi
  "Find the nearest ANSI keyword for an [r g b] triple."
  [rgb]
  (first (reduce (fn [[best-kw best-d] [kw ref-rgb]]
                   (let [d (rgb-distance-sq rgb ref-rgb)]
                     (if (< d best-d) [kw d] [best-kw best-d])))
                 [:white Integer/MAX_VALUE]
                 ansi-reference)))

(def ^:private indexed-to-rgb
  "Lookup table: ANSI-256 index → approximate [r g b].
   Indices 0-7: standard colors, 8-15: bright, 16-231: color cube, 232-255: grayscale."
  (let [base [[0 0 0] [205 0 0] [0 205 0] [205 205 0]
              [0 0 238] [205 0 205] [0 205 205] [229 229 229]
              [127 127 127] [255 0 0] [0 255 0] [255 255 0]
              [92 92 255] [255 0 255] [0 255 255] [255 255 255]]
        cube (for [r (range 6) g (range 6) b (range 6)]
               [(if (zero? r) 0 (+ 55 (* r 40)))
                (if (zero? g) 0 (+ 55 (* g 40)))
                (if (zero? b) 0 (+ 55 (* b 40)))])
        grays (for [i (range 24)]
                (let [v (+ 8 (* i 10))] [v v v]))]
    (vec (concat base cube grays))))

(defn degrade-color
  "Degrade a color value to match the given color depth.
   - :true-color  → pass through (keywords, integers, [r g b] all work)
   - :256         → convert [r g b] to nearest 256-index; pass keywords/integers
   - :16          → convert everything to nearest ANSI keyword"
  [color depth]
  (cond
    ;; Keywords always pass through (valid at all depths)
    (keyword? color) color

    ;; True-color: everything passes through
    (= depth :true-color) color

    ;; 256-color: pass integers, convert RGB to nearest indexed
    (= depth :256)
    (cond
      (integer? color) color
      (vector? color)
      (let [[r g b] color]
        ;; Find nearest 256-color index by distance
        (first (reduce (fn [[best-idx best-d] [idx rgb-ref]]
                         (let [d (rgb-distance-sq [r g b] rgb-ref)]
                           (if (< d best-d) [idx d] [best-idx best-d])))
                       [0 Integer/MAX_VALUE]
                       (map-indexed vector indexed-to-rgb))))
      :else color)

    ;; 16-color: degrade everything to keywords
    :else
    (cond
      (integer? color) (nearest-ansi (get indexed-to-rgb color [128 128 128]))
      (vector? color)  (nearest-ansi color)
      :else            :default)))

(defn resolve-theme-colors
  "Walk a theme map and degrade all color values based on terminal depth.
   Call once at theme resolution time."
  [theme depth]
  (if (= depth :true-color)
    theme
    (reduce-kv (fn [m k v] (assoc m k (degrade-color v depth)))
               {} theme)))

;; ─────────────────────────────────────────────────────────────────────────────
;; Miniforge brand palette
;;
;; From miniforge.ai brand swatches:
;;   #595959  (89,89,89)    — dark gray (steel)
;;   #F2B90C  (242,185,12)  — gold / amber
;;   #F2541B  (242,84,27)   — orange-red (flame)
;;   #F2220F  (242,34,15)   — red (hot)
;;   #D9D9D9  (217,217,217) — light gray (ash)

(def miniforge-palette
  "Miniforge brand color palette — RGB values."
  {:steel     [89 89 89]
   :gold      [242 185 12]
   :flame     [242 84 27]
   :hot       [242 34 15]
   :ash       [217 217 217]
   ;; Derived shades
   :charcoal  [35 30 28]
   :ember     [180 60 20]
   :parchment [240 235 225]
   :warm-gray [120 115 110]
   :dark-gold [190 145 10]})

;; ─────────────────────────────────────────────────────────────────────────────
;; Theme definitions

(def dark-theme
  "Miniforge brand dark: charcoal background, gold/flame accents."
  (let [p miniforge-palette]
    {;; Base colors
     :bg              (:charcoal p)
     :fg              (:ash p)
     :fg-dim          (:warm-gray p)
     :fg-muted        (:steel p)

     ;; Status indicators
     :status/running  (:gold p)
     :status/success  :green
     :status/failed   (:hot p)
     :status/blocked  (:flame p)
     :status/pending  (:ash p)
     :status/skipped  (:steel p)

     ;; UI chrome
     :border          (:steel p)
     :border-focus    (:gold p)
     :title           (:gold p)
     :title-focus     (:flame p)
     :header          (:flame p)
     :row-fg          (:ash p)
     :row-bg          (:charcoal p)
     :selected-bg     (:flame p)
     :selected-fg     :white

     ;; Progress
     :progress-fill   (:gold p)
     :progress-empty  (:steel p)

     ;; Kanban columns
     :kanban/blocked  (:hot p)
     :kanban/pending  (:gold p)
     :kanban/running  (:flame p)
     :kanban/done     :green

     ;; Sparkline
     :sparkline       (:gold p)

     ;; Command / search bar
     :command-bg      (:charcoal p)
     :command-fg      (:gold p)
     :search-match    (:flame p)}))

(def light-theme
  "Miniforge brand light: parchment background, ember/brown accents."
  (let [p miniforge-palette]
    {;; Base colors
     :bg              (:parchment p)
     :fg              (:charcoal p)
     :fg-dim          (:steel p)
     :fg-muted        (:warm-gray p)

     ;; Status indicators
     :status/running  :blue
     :status/success  :green
     :status/failed   (:hot p)
     :status/blocked  (:flame p)
     :status/pending  (:charcoal p)
     :status/skipped  (:steel p)

     ;; UI chrome
     :border          (:warm-gray p)
     :border-focus    (:flame p)
     :title           (:ember p)
     :title-focus     (:flame p)
     :header          (:ember p)
     :row-fg          (:charcoal p)
     :row-bg          (:parchment p)
     :selected-bg     (:flame p)
     :selected-fg     :white

     ;; Progress
     :progress-fill   (:ember p)
     :progress-empty  (:warm-gray p)

     ;; Kanban columns
     :kanban/blocked  (:hot p)
     :kanban/pending  (:gold p)
     :kanban/running  :blue
     :kanban/done     :green

     ;; Sparkline
     :sparkline       (:ember p)

     ;; Command / search bar
     :command-bg      (:parchment p)
     :command-fg      (:charcoal p)
     :search-match    (:flame p)}))

(def high-contrast-theme
  "High-contrast dark: pure black bg, white fg, bright ANSI accents."
  {;; Base colors
   :bg              :black
   :fg              :white
   :fg-dim          :cyan
   :fg-muted        :green

   ;; Status indicators
   :status/running  :cyan
   :status/success  :green
   :status/failed   :red
   :status/blocked  :yellow
   :status/pending  :white
   :status/skipped  :white

   ;; UI chrome
   :border          :green
   :border-focus    :cyan
   :title           :green
   :title-focus     :cyan
   :header          :cyan
   :row-fg          :white
   :row-bg          :black
   :selected-bg     :blue
   :selected-fg     :white

   ;; Progress
   :progress-fill   :cyan
   :progress-empty  :white

   ;; Kanban columns
   :kanban/blocked  :red
   :kanban/pending  :yellow
   :kanban/running  :cyan
   :kanban/done     :green

   ;; Sparkline
   :sparkline       :cyan

   ;; Command / search bar
   :command-bg      :black
   :command-fg      :white
   :search-match    :yellow})

(def high-contrast-light-theme
  "High-contrast light: pure white bg, black fg, bold ANSI accents."
  {;; Base colors
   :bg              :white
   :fg              :black
   :fg-dim          :blue
   :fg-muted        :magenta

   ;; Status indicators
   :status/running  :blue
   :status/success  :green
   :status/failed   :red
   :status/blocked  :yellow
   :status/pending  :black
   :status/skipped  :black

   ;; UI chrome
   :border          :blue
   :border-focus    :magenta
   :title           :blue
   :title-focus     :magenta
   :header          :blue
   :row-fg          :black
   :row-bg          :white
   :selected-bg     :blue
   :selected-fg     :white

   ;; Progress
   :progress-fill   :blue
   :progress-empty  :black

   ;; Kanban columns
   :kanban/blocked  :red
   :kanban/pending  :yellow
   :kanban/running  :blue
   :kanban/done     :green

   ;; Sparkline
   :sparkline       :blue

   ;; Command / search bar
   :command-bg      :white
   :command-fg      :black
   :search-match    :red})

(def themes
  "Registry of available themes."
  {:dark                 dark-theme
   :light                light-theme
   :high-contrast        high-contrast-theme
   :high-contrast-light  high-contrast-light-theme
   :default              dark-theme})

(def default-theme
  "Default theme for miniforge TUI."
  dark-theme)

(defn get-theme
  "Resolve a theme keyword to a theme map.
   Falls back to dark-theme for unknown keys."
  [k]
  (get themes k dark-theme))

(defn resolve-color
  "Resolve a theme key to a color value (keyword, integer, or [r g b]).
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
