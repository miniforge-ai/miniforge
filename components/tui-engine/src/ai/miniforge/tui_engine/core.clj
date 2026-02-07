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

(ns ai.miniforge.tui-engine.core
  "Elm-architecture runtime for the TUI engine.

   Implements the core application loop:
   1. Messages arrive via dispatch! (from input polling or external subscriptions)
   2. The update function produces a new model (pure)
   3. The view function produces a render tree (pure)
   4. The render tree is flattened to a cell buffer and diffed against previous
   5. Changed cells are written to the screen

   Throttling: user input renders immediately. External messages are batched
   and rendered at a configurable frame rate (default 1 fps)."
  (:require
   [ai.miniforge.tui-engine.screen :as screen]
   [ai.miniforge.tui-engine.input :as input]
   [ai.miniforge.tui-engine.layout :as layout]))

;------------------------------------------------------------------------------ Layer 0
;; App state structure

(defn create-app
  "Create a TUI application from a config map.

   Config:
   - :init          - (fn [] model)
   - :update        - (fn [model msg] model')
   - :view          - (fn [model [cols rows]] cell-buffer)
   - :subscriptions - (fn [dispatch-fn] cleanup-fn) -- optional
   - :screen        - IScreen implementation (optional, auto-created if nil)
   - :fps           - Target frame rate for external messages (default 1)

   Returns an atom holding the app state."
  [{:keys [init update view subscriptions screen fps]
    :or {fps 1}}]
  (atom {:model          (init)
         :update-fn      update
         :view-fn        view
         :subscriptions  subscriptions
         :screen         (or screen (screen/create-screen))
         :fps            fps
         :running?       false
         :input-thread   nil
         :unsub-fn       nil
         :prev-buffer    nil}))

;------------------------------------------------------------------------------ Layer 1
;; Rendering

(defn render!
  "Render the current model to screen. Only writes changed cells."
  [app-state]
  (let [{:keys [model view-fn screen]} app-state
        [cols rows] (screen/get-size screen)
        new-buffer (view-fn model [cols rows])]
    (screen/clear! screen)
    ;; Write all cells (Lanterna handles delta internally via refresh)
    (doseq [row (range (min rows (count new-buffer)))
            col (range (min cols (count (nth new-buffer row))))]
      (let [{:keys [char fg bg bold?]} (get-in new-buffer [row col])]
        (when char
          (screen/put-string! screen col row (str char) fg bg (boolean bold?)))))
    (screen/refresh! screen)
    (assoc app-state :prev-buffer new-buffer)))

;------------------------------------------------------------------------------ Layer 2
;; Message dispatch

(defn dispatch!
  "Dispatch a message into the Elm update loop.
   Calls update, then re-renders."
  [app msg]
  (swap! app (fn [state]
               (let [new-model ((:update-fn state) (:model state) msg)]
                 (render! (assoc state :model new-model))))))

(defn get-model
  "Get current model snapshot."
  [app]
  (:model @app))

;------------------------------------------------------------------------------ Layer 3
;; Input polling thread

(defn- start-input-thread!
  "Start a daemon thread that polls for keyboard input and dispatches messages."
  [app]
  (let [thread (Thread.
                (fn []
                  (try
                    (while (:running? @app)
                      (let [screen (:screen @app)
                            key (input/poll-key screen)]
                        (if key
                          (dispatch! app [:input key])
                          (Thread/sleep 16)))) ; ~60Hz polling
                    (catch InterruptedException _)
                    (catch Exception _))))]
    (.setDaemon thread true)
    (.setName thread "tui-input-poll")
    (.start thread)
    thread))

;------------------------------------------------------------------------------ Layer 4
;; Application lifecycle

(defn start!
  "Start the TUI application.
   1. Enter alternate screen
   2. Start input polling thread
   3. Register subscriptions
   4. Render initial view

   Returns the app atom."
  [app]
  (let [screen (:screen @app)]
    (screen/start-screen! screen)
    (swap! app assoc :running? true)
    ;; Initial render
    (swap! app render!)
    ;; Start input thread
    (let [thread (start-input-thread! app)]
      (swap! app assoc :input-thread thread))
    ;; Register subscriptions
    (when-let [sub-fn (:subscriptions @app)]
      (let [unsub (sub-fn (fn [msg] (dispatch! app msg)))]
        (swap! app assoc :unsub-fn unsub)))
    ;; JVM shutdown hook for terminal restoration
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn []
                                 (try
                                   (screen/stop-screen! screen)
                                   (catch Exception _)))))
    app))

(defn stop!
  "Stop the TUI application.
   1. Unregister subscriptions
   2. Stop input thread
   3. Exit alternate screen

   Safe to call multiple times."
  [app]
  (when (:running? @app)
    (swap! app assoc :running? false)
    ;; Unsubscribe
    (when-let [unsub (:unsub-fn @app)]
      (try (unsub) (catch Exception _)))
    ;; Stop input thread
    (when-let [thread (:input-thread @app)]
      (.interrupt thread))
    ;; Restore terminal
    (try
      (screen/stop-screen! (:screen @app))
      (catch Exception _))
    (swap! app assoc :unsub-fn nil :input-thread nil)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Minimal app example
  (def my-app
    (create-app
     {:init   (fn [] {:count 0})
      :update (fn [model msg]
                (case (first msg)
                  :input (let [key (second msg)]
                           (case key
                             :key/j (update model :count inc)
                             :key/k (update model :count dec)
                             model))
                  model))
      :view   (fn [model [cols rows]]
                (layout/text [cols rows]
                             (str "Count: " (:count model))))}))

  (start! my-app)
  ;; Press j/k to increment/decrement
  (stop! my-app)

  :leave-this-here)
