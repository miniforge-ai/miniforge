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

(ns ai.miniforge.tui-engine.interface
  "Public API for the TUI engine component.

   Provides a terminal-agnostic Elm-architecture TUI framework:
   - create-app  : Define an application with init/update/view/subscriptions
   - start!      : Launch the TUI (enters alternate screen, starts input loop)
   - stop!       : Shut down the TUI (restores terminal)
   - dispatch!   : Send a message into the update loop
   - get-model   : Read current application model

   The engine is domain-agnostic -- it knows nothing about workflows,
   agents, or events. Those are wired in via the tui-views component."
  (:require
   [ai.miniforge.tui-engine.core :as core]
   [ai.miniforge.tui-engine.runtime :as runtime]
   [ai.miniforge.tui-engine.screen :as screen]
   [ai.miniforge.tui-engine.input :as input]
   [ai.miniforge.tui-engine.log :as log]
   [ai.miniforge.tui-engine.style :as style]))

;; ─────────────────────────────────────────────────────────────────────────────
;; Application lifecycle

(defn create-app
  "Create a TUI application.

   config map:
   - :init           - (fn [] model) -- returns initial model state
   - :update         - (fn [model msg] model') -- pure state transition
   - :view           - (fn [model size] render-tree) -- pure render function
   - :subscriptions  - (fn [app] unsub-fn) -- registers external msg sources
   - :effect-handler - (fn [effect-map] msg-vector) -- executes side-effects async
   - :screen         - Optional: IScreen instance (for testing with MockScreen)

   Side-effect protocol:
   When update returns a model with a :side-effect key, the runtime strips it
   and calls effect-handler on a background thread. The handler returns a result
   message that is dispatched back into the update loop.

   Returns: app atom suitable for start!, stop!, dispatch!."
  [config]
  (core/create-app config))

(defn start!
  "Start the TUI application.
   Enters alternate screen, starts input polling, renders initial view.
   Returns the app atom."
  [app]
  (runtime/start! app))

(defn stop!
  "Stop the TUI application.
   Unregisters subscriptions, stops input polling, exits alternate screen.
   Safe to call multiple times."
  [app]
  (runtime/stop! app))

(defn dispatch!
  "Dispatch a message to the application's update function.
   The message flows through: update -> view -> render."
  [app msg]
  (runtime/dispatch! app msg))

(defn get-model
  "Get the current application model (read-only snapshot)."
  [app]
  (runtime/get-model app))

;; ─────────────────────────────────────────────────────────────────────────────
;; Logging

(def set-log-level!
  "Set the minimum log level for the TUI engine. Accepts :debug :info :warn :error.
   Useful for enabling debug logging at startup via CLI flag or runtime command."
  log/set-level!)

;; ─────────────────────────────────────────────────────────────────────────────
;; Re-exports for convenience

;; Screen

(def create-screen
  "Create a Lanterna terminal screen for rendering.
   Dynamically loads the Lanterna implementation (for Babashka compatibility).
   Returns an IScreen instance."
  screen/create-screen)

(def create-mock-screen
  "Create a mock screen for testing.
   Returns a MockScreen instance that captures renders to a cell buffer."
  screen/create-mock-screen)

(def mock-enqueue-input!
  "Enqueue input events into a mock screen's input queue.
   For testing keyboard interactions."
  screen/mock-enqueue-input!)

(def mock-get-cells
  "Get the current cell buffer from a mock screen.
   Returns a 2D vector of cell maps."
  screen/mock-get-cells)

(def mock-read-line
  "Read a line of text from a mock screen's cell buffer.
   Returns a string representation of the specified row."
  screen/mock-read-line)

;; Style / Themes

(def default-theme
  "Default color theme for TUI rendering (miniforge brand dark).
   Map of semantic color names to color values (keywords, ints, or [r g b])."
  style/default-theme)

(def dark-theme
  "Miniforge brand dark: charcoal background, gold/flame accents."
  (style/get-theme :dark))

(def light-theme
  "Miniforge brand light: parchment background, ember accents."
  (style/get-theme :light))

(def high-contrast-theme
  "High-contrast dark: pure black bg, white fg, bright ANSI accents."
  (style/get-theme :high-contrast))

(def high-contrast-light-theme
  "High-contrast light: pure white bg, black fg, bold ANSI accents."
  (style/get-theme :high-contrast-light))

(def themes
  "Registry of available themes: {:dark, :light, :high-contrast, :high-contrast-light, :default}."
  style/themes)

(def get-theme
  "Resolve a theme keyword to a theme map.
   (get-theme :dark) => dark-theme, (get-theme :light) => light-theme."
  style/get-theme)

(def resolve-style
  "Resolve a style map into Lanterna TextCharacter attributes.
   Converts {:fg :bg :bold?} to Lanterna-compatible representation."
  style/resolve-style)

(def semantic-status-colors
  "Fixed status colors that remain constant across all themes.
   Keys: :status-color/pass, :status-color/fail, :status-color/warning,
         :status-color/info, :status-color/neutral."
  style/semantic-status-colors)

(def resolve-status-color
  "Resolve a semantic status color keyword to its fixed ANSI color.
   Unlike theme colors, these never change."
  style/resolve-status-color)

;; Input

(def normalize-key
  "Normalize Lanterna KeyStroke to a semantic keyword.
   Maps raw key events to keywords like :key/j, :key/enter, :key/escape."
  input/normalize-key)
