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

   Real terminal implementation for screen/create-screen. Java classes are
   resolved inside the factory path so this namespace remains loadable in
   Babashka compatibility checks that never instantiate a real terminal."
  (:require
   [ai.miniforge.tui-engine.screen.protocol :as protocol]))

;; ─────────────────────────────────────────────────────────────────────────────
;; Reflective Lanterna class access

(defn- class-named [class-name]
  (Class/forName class-name))

(defn- static-field [class-name field-name]
  (.get (.getField (class-named class-name) field-name) nil))

(defn- arity-match? [arity member]
  (= arity (count (.getParameterTypes member))))

(defn- construct [class-name & args]
  (let [constructor (->> (.getConstructors (class-named class-name))
                         (filter (partial arity-match? (count args)))
                         first)]
    (when-not constructor
      (throw (ex-info "No matching Lanterna constructor."
                      {:class class-name
                       :arity (count args)})))
    (.newInstance constructor (object-array args))))

(defn- invoke [target method-name & args]
  (let [method (->> (.getMethods (class target))
                    (filter #(= method-name (.getName %)))
                    (filter (partial arity-match? (count args)))
                    first)]
    (when-not method
      (throw (ex-info "No matching Lanterna method."
                      {:class (.getName (class target))
                       :method method-name
                       :arity (count args)})))
    (.invoke method target (object-array args))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Color mapping

(def color-map
  "Map from keyword colors to Lanterna TextColor.ANSI constants."
  {:black   "BLACK"
   :red     "RED"
   :green   "GREEN"
   :yellow  "YELLOW"
   :blue    "BLUE"
   :magenta "MAGENTA"
   :cyan    "CYAN"
   :white   "WHITE"
   :default "DEFAULT"})

(defn- ansi-color [field-name]
  (static-field "com.googlecode.lanterna.TextColor$ANSI" field-name))

(defn resolve-lanterna-color
  "Resolve a color value to a Lanterna TextColor.
   Accepts:
   - keyword  (:cyan, :red, etc.)  → TextColor.ANSI
   - integer  (0–255)              → TextColor.Indexed
   - vector   [r g b]              → TextColor.RGB"
  [color]
  (cond
    (keyword? color) (ansi-color (get color-map color "DEFAULT"))
    (integer? color) (construct "com.googlecode.lanterna.TextColor$Indexed" (int color))
    (vector? color)  (let [[r g b] color]
                       (construct "com.googlecode.lanterna.TextColor$RGB"
                                  (int r) (int g) (int b)))
    :else            (ansi-color "DEFAULT")))

;; ─────────────────────────────────────────────────────────────────────────────
;; SGR arrays

(defn- sgr-array [field-names]
  (let [sgr-class (class-named "com.googlecode.lanterna.SGR")
        arr (make-array sgr-class (count field-names))]
    (doseq [[idx field-name] (map-indexed vector field-names)]
      (aset arr idx (static-field "com.googlecode.lanterna.SGR" field-name)))
    arr))

(def ^:private sgr-bold (delay (sgr-array ["BOLD"])))
(def ^:private sgr-none (delay (sgr-array [])))

;; ─────────────────────────────────────────────────────────────────────────────
;; Lanterna implementation

(defrecord LanternaScreen [screen]
  protocol/IScreen
  (start-screen! [_]
    (invoke screen "startScreen"))

  (stop-screen! [_]
    (invoke screen "stopScreen"))

  (get-size [_]
    ;; doResizeIfNecessary picks up SIGWINCH and updates the cached size.
    ;; Returns non-null only when the terminal was actually resized.
    (invoke screen "doResizeIfNecessary")
    (let [size (invoke screen "getTerminalSize")]
      [(invoke size "getColumns") (invoke size "getRows")]))

  (put-string! [_ col row text fg bg bold?]
    (let [fg-color (resolve-lanterna-color fg)
          bg-color (resolve-lanterna-color bg)
          sgr-arr (if bold? @sgr-bold @sgr-none)]
      (dotimes [i (count text)]
        (let [ch (.charAt ^String text i)
              tc (construct "com.googlecode.lanterna.TextCharacter"
                            ch fg-color bg-color sgr-arr)]
          (invoke screen "setCharacter" (+ col i) row tc)))))

  (clear! [_]
    (invoke screen "clear"))

  (refresh! [_]
    (invoke screen "refresh"
            (static-field "com.googlecode.lanterna.screen.Screen$RefreshType" "COMPLETE")))

  (poll-input [_]
    (when-let [key (invoke screen "pollInput")]
      (let [kind (invoke key "getKeyType")
            shift? (invoke key "isShiftDown")]
        (cond
          (= kind (static-field "com.googlecode.lanterna.input.KeyType" "Character"))
          {:type :character :char (invoke key "getCharacter")}

          (= kind (static-field "com.googlecode.lanterna.input.KeyType" "Enter"))
          {:type :enter}

          (= kind (static-field "com.googlecode.lanterna.input.KeyType" "Escape"))
          {:type :escape}

          (= kind (static-field "com.googlecode.lanterna.input.KeyType" "Backspace"))
          {:type :backspace}

          (= kind (static-field "com.googlecode.lanterna.input.KeyType" "ArrowUp"))
          {:type (if shift? :shift-arrow-up :arrow-up)}

          (= kind (static-field "com.googlecode.lanterna.input.KeyType" "ArrowDown"))
          {:type (if shift? :shift-arrow-down :arrow-down)}

          (= kind (static-field "com.googlecode.lanterna.input.KeyType" "ArrowLeft"))
          {:type :arrow-left}

          (= kind (static-field "com.googlecode.lanterna.input.KeyType" "ArrowRight"))
          {:type :arrow-right}

          (= kind (static-field "com.googlecode.lanterna.input.KeyType" "Tab"))
          {:type :tab}

          (= kind (static-field "com.googlecode.lanterna.input.KeyType" "ReverseTab"))
          {:type :reverse-tab}

          (= kind (static-field "com.googlecode.lanterna.input.KeyType" "EOF"))
          {:type :eof}

          :else
          {:type :unknown :raw (str kind)})))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Factory

(defn create-lanterna-screen
  "Create a Lanterna terminal screen."
  [_opts]
  (let [factory (construct "com.googlecode.lanterna.terminal.DefaultTerminalFactory")
        terminal (invoke factory "createTerminal")
        screen (construct "com.googlecode.lanterna.screen.TerminalScreen" terminal)]
    (->LanternaScreen screen)))
