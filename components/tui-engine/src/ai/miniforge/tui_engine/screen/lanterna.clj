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

(ns ai.miniforge.tui-engine.screen.lanterna
  "Lanterna Screen implementation.

   Loaded dynamically by screen/create-screen to avoid Java class loading
   at namespace load time (required for Babashka/GraalVM compatibility)."
  (:require
   [ai.miniforge.tui-engine.screen :as screen])
  (:import
   [com.googlecode.lanterna TextCharacter TextColor$ANSI TextColor$Indexed TextColor$RGB]
   [com.googlecode.lanterna.screen TerminalScreen Screen$RefreshType]
   [com.googlecode.lanterna.terminal DefaultTerminalFactory]))

;; ─────────────────────────────────────────────────────────────────────────────
;; Color mapping

(def color-map
  "Map from keyword colors to Lanterna TextColor.ANSI constants."
  {:black   TextColor$ANSI/BLACK
   :red     TextColor$ANSI/RED
   :green   TextColor$ANSI/GREEN
   :yellow  TextColor$ANSI/YELLOW
   :blue    TextColor$ANSI/BLUE
   :magenta TextColor$ANSI/MAGENTA
   :cyan    TextColor$ANSI/CYAN
   :white   TextColor$ANSI/WHITE
   :default TextColor$ANSI/DEFAULT})

(defn resolve-lanterna-color
  "Resolve a color value to a Lanterna TextColor.
   Accepts:
   - keyword  (:cyan, :red, etc.)  → TextColor.ANSI
   - integer  (0–255)              → TextColor.Indexed
   - vector   [r g b]              → TextColor.RGB"
  [color]
  (cond
    (keyword? color) (get color-map color TextColor$ANSI/DEFAULT)
    (integer? color) (TextColor$Indexed. (int color))
    (vector? color)  (let [[r g b] color]
                       (TextColor$RGB. (int r) (int g) (int b)))
    :else            TextColor$ANSI/DEFAULT))

;; ─────────────────────────────────────────────────────────────────────────────
;; Pre-allocated SGR arrays (avoid reflection + allocation on every character)

(def ^{:tag "[Lcom.googlecode.lanterna.SGR;"} sgr-bold
  (into-array com.googlecode.lanterna.SGR [com.googlecode.lanterna.SGR/BOLD]))

(def ^{:tag "[Lcom.googlecode.lanterna.SGR;"} sgr-none
  (into-array com.googlecode.lanterna.SGR []))

;; ─────────────────────────────────────────────────────────────────────────────
;; Lanterna implementation

(defrecord LanternaScreen [^TerminalScreen screen]
  screen/IScreen
  (start-screen! [_]
    (.startScreen screen))

  (stop-screen! [_]
    (.stopScreen screen))

  (get-size [_]
    ;; doResizeIfNecessary picks up SIGWINCH and updates the cached size.
    ;; Returns non-null only when the terminal was actually resized.
    (.doResizeIfNecessary screen)
    (let [size (.getTerminalSize screen)]
      [(.getColumns size) (.getRows size)]))

  (put-string! [_ col row text fg bg bold?]
    (let [fg-color (resolve-lanterna-color fg)
          bg-color (resolve-lanterna-color bg)
          sgr-arr (if bold? sgr-bold sgr-none)]
      (dotimes [i (count text)]
        (let [ch (.charAt ^String text i)
              tc (TextCharacter. ch fg-color bg-color sgr-arr)]
          (.setCharacter screen (+ col i) row tc)))))

  (clear! [_]
    (.clear screen))

  (refresh! [_]
    (.refresh screen Screen$RefreshType/DELTA))

  (poll-input [_]
    (when-let [key (.pollInput screen)]
      (let [kind (.getKeyType key)]
        (cond
          (= kind com.googlecode.lanterna.input.KeyType/Character)
          {:type :character :char (.getCharacter key)}

          (= kind com.googlecode.lanterna.input.KeyType/Enter)
          {:type :enter}

          (= kind com.googlecode.lanterna.input.KeyType/Escape)
          {:type :escape}

          (= kind com.googlecode.lanterna.input.KeyType/Backspace)
          {:type :backspace}

          (= kind com.googlecode.lanterna.input.KeyType/ArrowUp)
          {:type :arrow-up}

          (= kind com.googlecode.lanterna.input.KeyType/ArrowDown)
          {:type :arrow-down}

          (= kind com.googlecode.lanterna.input.KeyType/ArrowLeft)
          {:type :arrow-left}

          (= kind com.googlecode.lanterna.input.KeyType/ArrowRight)
          {:type :arrow-right}

          (= kind com.googlecode.lanterna.input.KeyType/Tab)
          {:type :tab}

          (= kind com.googlecode.lanterna.input.KeyType/ReverseTab)
          {:type :reverse-tab}

          (= kind com.googlecode.lanterna.input.KeyType/EOF)
          {:type :eof}

          :else
          {:type :unknown :raw (str kind)})))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Factory

(defn create-lanterna-screen
  "Create a Lanterna terminal screen."
  [_opts]
  (let [factory (DefaultTerminalFactory.)
        terminal (.createTerminal factory)
        screen (TerminalScreen. terminal)]
    (->LanternaScreen screen)))
