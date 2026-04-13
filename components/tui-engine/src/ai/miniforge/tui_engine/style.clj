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

   Built-in themes are defined in config/tui/themes.edn (data-driven).
   Custom themes can be added via ~/.miniforge/themes/*.edn.
   Themes are pure data maps — no side effects."
  (:require
   [ai.miniforge.config.interface :as config]
   [clojure.java.io :as io]
   [clojure.edn :as edn]))

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

(def ansi-reference
  "ANSI 16 base colors with approximate RGB values for nearest-match."
  [[:black   [0 0 0]]
   [:red     [205 0 0]]
   [:green   [0 205 0]]
   [:yellow  [205 205 0]]
   [:blue    [0 0 238]]
   [:magenta [205 0 205]]
   [:cyan    [0 205 205]]
   [:white   [229 229 229]]])

(defn rgb-distance-sq
  "Squared Euclidean distance between two [r g b] triples."
  [[r1 g1 b1] [r2 g2 b2]]
  (+ (* (- r1 r2) (- r1 r2))
     (* (- g1 g2) (- g1 g2))
     (* (- b1 b2) (- b1 b2))))

(defn nearest-ansi
  "Find the nearest ANSI keyword for an [r g b] triple."
  [rgb]
  (first (reduce (fn [[best-kw best-d] [kw ref-rgb]]
                   (let [d (rgb-distance-sq rgb ref-rgb)]
                     (if (< d best-d) [kw d] [best-kw best-d])))
                 [:white Integer/MAX_VALUE]
                 ansi-reference)))

(def indexed-to-rgb
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
;; Data-driven theme loading
;;
;; Built-in themes are loaded from config/tui/themes.edn at namespace init.
;; Custom themes from ~/.miniforge/themes/*.edn are merged in at startup.
;; This makes themes extensible like policy packs — drop EDN, get a theme.

(def builtin-themes
  "Load built-in themes from EDN resource."
  (if-let [r (io/resource "config/tui/themes.edn")]
    (edn/read-string (slurp r))
    {}))

(defn load-user-themes
  "Load custom themes from ~/.miniforge/themes/*.edn.
   Each file should contain a single map: {theme-keyword theme-map}."
  []
  (let [dir (io/file (config/miniforge-home) "themes")]
    (if (and (.exists dir) (.isDirectory dir))
      (->> (.listFiles dir)
           (filter #(.endsWith (.getName %) ".edn"))
           (reduce (fn [acc f]
                     (try
                       (merge acc (edn/read-string (slurp f)))
                       (catch Exception _ acc)))
                   {}))
      {})))

(def themes
  "Registry of available themes. Built-in + user custom.
   User themes override built-in themes of the same name."
  (let [user (load-user-themes)]
    (merge builtin-themes user {:default (get builtin-themes :dark {})})))

(def default-theme
  "Default theme for miniforge TUI."
  (get themes :dark {}))

(defn get-theme
  "Resolve a theme keyword to a theme map.
   Falls back to dark-theme for unknown keys."
  [k]
  (get themes k default-theme))

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

;; ─────────────────────────────────────────────────────────────────────────────
;; Semantic status colors — theme-independent
;;
;; These colors encode universal meaning (pass/fail/warning) and remain
;; constant across all UI themes. Users learn to associate green with pass,
;; red with fail, etc., and those associations must not shift when themes change.

(def semantic-status-colors
  "Fixed status colors that remain constant across all themes.
   Use these for any pass/fail/warning/info status indicators."
  {:status-color/pass    :green
   :status-color/fail    :red
   :status-color/warning :yellow
   :status-color/info    :cyan
   :status-color/neutral :default})

(defn resolve-status-color
  "Resolve a semantic status color keyword to its fixed ANSI color.
   Unlike theme colors, these never change."
  [status-key]
  (get semantic-status-colors status-key :default))
