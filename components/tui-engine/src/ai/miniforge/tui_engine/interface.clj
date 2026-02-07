;; Copyright 2025 miniforge.ai
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
   [ai.miniforge.tui-engine.screen :as screen]
   [ai.miniforge.tui-engine.input :as input]
   [ai.miniforge.tui-engine.style :as style]))

;; ─────────────────────────────────────────────────────────────────────────────
;; Application lifecycle

(defn create-app
  "Create a TUI application.

   config map:
   - :init          - (fn [] model) -- returns initial model state
   - :update        - (fn [model msg] model') -- pure state transition
   - :view          - (fn [model size] render-tree) -- pure render function
   - :subscriptions - (fn [app] unsub-fn) -- registers external msg sources
   - :screen        - Optional: IScreen instance (for testing with MockScreen)

   Returns: app atom suitable for start!, stop!, dispatch!."
  [config]
  (core/create-app config))

(defn start!
  "Start the TUI application.
   Enters alternate screen, starts input polling, renders initial view.
   Returns the app atom."
  [app]
  (core/start! app))

(defn stop!
  "Stop the TUI application.
   Unregisters subscriptions, stops input polling, exits alternate screen.
   Safe to call multiple times."
  [app]
  (core/stop! app))

(defn dispatch!
  "Dispatch a message to the application's update function.
   The message flows through: update -> view -> render."
  [app msg]
  (core/dispatch! app msg))

(defn get-model
  "Get the current application model (read-only snapshot)."
  [app]
  (core/get-model app))

;; ─────────────────────────────────────────────────────────────────────────────
;; Re-exports for convenience

;; Screen
(def create-screen screen/create-screen)
(def create-mock-screen screen/create-mock-screen)
(def mock-enqueue-input! screen/mock-enqueue-input!)
(def mock-get-cells screen/mock-get-cells)
(def mock-read-line screen/mock-read-line)

;; Style
(def default-theme style/default-theme)
(def resolve-style style/resolve-style)

;; Input
(def normalize-key input/normalize-key)
