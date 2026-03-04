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

(ns ai.miniforge.tui-engine.core
  "Elm-architecture runtime for the TUI engine.

   Implements the core application loop:
   1. Messages arrive via dispatch! (from input polling or external subscriptions)
   2. The update function produces a new model (pure, in dosync)
   3. An add-watch on the model ref triggers rendering
   4. The render function diffs the cell buffer and writes only changed cells

   Concurrency model:
   - model and prev-buffer are refs, coordinated via dosync
   - add-watch on model-ref triggers rendering after every state transition
   - Rendering is serialized via a lock to prevent concurrent screen writes
   - Side-effects are extracted during dosync and executed asynchronously

   Throttling: user input renders immediately. External messages are batched
   and rendered at a configurable frame rate (default 1 fps)."
  (:require
   [ai.miniforge.tui-engine.screen :as screen]
   [ai.miniforge.tui-engine.input :as input]
   [ai.miniforge.tui-engine.layout :as layout]))

;------------------------------------------------------------------------------ Layer 0
;; Rendering primitives

(defn- paint-screen!
  "Impure: diff new-buffer against prev-buffer and write changed cells to screen.
   Must be called under the render lock to prevent concurrent screen writes.
   Returns new-buffer (to be stored as prev-buffer)."
  [screen prev-buffer new-buffer]
  (let [buf-rows (count new-buffer)
        buf-cols (if (pos? buf-rows) (count (first new-buffer)) 0)
        [scr-cols scr-rows] (screen/get-size screen)
        resized? (or (nil? prev-buffer)
                     (not= buf-rows (count prev-buffer))
                     (and (pos? buf-rows) (seq prev-buffer)
                          (not= buf-cols (count (first prev-buffer)))))]
    ;; Only clear on first render or resize — never on normal frames
    (when resized? (screen/clear! screen))
    ;; Cell-level diff: only write cells that actually changed
    (doseq [row (range (min scr-rows buf-rows))
            col (range (min scr-cols (count (nth new-buffer row))))]
      (let [new-cell (get-in new-buffer [row col])
            old-cell (when-not resized? (get-in prev-buffer [row col]))]
        (when (or resized? (not= new-cell old-cell))
          (let [{:keys [char fg bg bold?]} new-cell]
            (when char
              (screen/put-string! screen col row (str char) fg bg (boolean bold?)))))))
    (screen/refresh! screen)
    new-buffer))

(defn- do-render!
  "Compute a new buffer from the model and paint it to screen.
   Updates buffer-ref transactionally. Serialized by the render lock."
  [{:keys [view-fn screen buffer-ref render-lock]} model]
  (let [[cols rows] (screen/get-size screen)
        new-buffer (view-fn model [cols rows])
        prev-buffer @buffer-ref
        painted (locking render-lock
                  (paint-screen! screen prev-buffer new-buffer))]
    (dosync (ref-set buffer-ref painted))))

;------------------------------------------------------------------------------ Layer 1
;; App state structure

(defn create-app
  "Create a TUI application from a config map.

   Config:
   - :init           - (fn [] model)
   - :update         - (fn [model msg] model')
   - :view           - (fn [model [cols rows]] cell-buffer)
   - :subscriptions  - (fn [dispatch-fn] cleanup-fn) -- optional
   - :effect-handler - (fn [effect-map] msg-vector) -- optional, executes side-effects
   - :screen         - IScreen implementation (optional, auto-created if nil)
   - :fps            - Target frame rate for external messages (default 1)

   Side-effect protocol:
   When update-fn returns a model with a :side-effect key, the runtime strips it
   and calls effect-handler on a background thread. The effect-handler should return
   a message vector (e.g. [:msg/prs-synced {:pr-items [...]}]) which is dispatched
   back into the update loop.

   Installs an add-watch on the model ref that triggers rendering on every
   state change. The watcher is the sole render trigger — dispatch! only
   does the pure model update.

   Returns an atom holding the app state."
  [{:keys [init update view subscriptions effect-handler screen fps]
    :or {fps 1}}]
  (let [model-ref   (ref (init))
        buffer-ref  (ref nil)
        render-lock (Object.)
        scr         (or screen (screen/create-screen))
        app-state   {:model-ref      model-ref
                     :buffer-ref     buffer-ref
                     :update-fn      update
                     :view-fn        view
                     :subscriptions  subscriptions
                     :effect-handler effect-handler
                     :screen         scr
                     :fps            fps
                     :running?       false
                     :input-thread   nil
                     :unsub-fn       nil
                     :render-lock    render-lock
                     :effect-threads (atom #{})}]
    ;; Install render watcher — fires after every model ref change
    (add-watch model-ref ::render
               (fn [_key _ref _old-model new-model]
                 (try
                   (do-render! app-state new-model)
                   (catch Exception _))))
    (atom app-state)))

;------------------------------------------------------------------------------ Layer 2
;; Public render entry point (for initial render and tests)

(defn render!
  "Render the current model to screen. Only writes changed cells.
   Uses cell-level diffing against prev-buffer to avoid full-screen
   rewrites that cause visible flashing.

   For backward compatibility with (swap! app render!) in tests.
   The model-ref watcher handles all dispatch-driven renders; this
   is only needed for the initial render in start!."
  [app-state]
  (let [{:keys [model-ref]} app-state]
    (do-render! app-state @model-ref)
    app-state))

;------------------------------------------------------------------------------ Layer 3
;; Side-effect execution

(defn- execute-side-effect!
  "Execute a side-effect on a background daemon thread.
   Calls effect-handler-fn with the effect map, then dispatches the result
   message back into the Elm loop via dispatch-fn.
   Tracks the thread in effect-threads so stop! can interrupt in-flight effects."
  [effect effect-handler-fn dispatch-fn effect-threads]
  (let [thread (Thread.
                (fn []
                  (try
                    (when-let [result-msg (effect-handler-fn effect)]
                      (dispatch-fn result-msg))
                    (catch InterruptedException _)
                    (catch Exception e
                      (when-not (Thread/interrupted)
                        (dispatch-fn [:msg/side-effect-error
                                      {:type (:type effect)
                                       :error (.getMessage e)}]))))
                  (when effect-threads
                    (swap! effect-threads disj (Thread/currentThread)))))]
    (.setDaemon thread true)
    (.setName thread (str "tui-effect-" (name (get effect :type "unknown"))))
    (when effect-threads
      (swap! effect-threads conj thread))
    (.start thread)))

;------------------------------------------------------------------------------ Layer 4
;; Message dispatch

(defn dispatch!
  "Dispatch a message into the Elm update loop.

   Uses dosync to atomically update the model ref. The add-watch
   installed in create-app triggers rendering after each state transition.
   dispatch! only does the pure model update — no screen I/O.

   Side-effect protocol:
   If the new model contains a :side-effect key, it is stripped from the model
   and executed asynchronously via the registered :effect-handler. The handler
   runs on a background thread and its result message is dispatched back into
   the update loop."
  [app msg]
  (let [{:keys [model-ref update-fn]} @app
        pending-effect (atom nil)]
    ;; Atomic model update — watcher fires after commit to handle rendering
    (dosync
     (let [new-model (update-fn @model-ref msg)
           fx (:side-effect new-model)
           clean-model (dissoc new-model :side-effect)]
       (when fx (reset! pending-effect fx))
       (ref-set model-ref clean-model)))
    ;; Side-effects executed outside the transaction
    (when-let [fx @pending-effect]
      (when-let [handler (:effect-handler @app)]
        (execute-side-effect! fx handler
                              (fn [result-msg] (dispatch! app result-msg))
                              (:effect-threads @app))))))

(defn get-model
  "Get current model snapshot."
  [app]
  @(:model-ref @app))

;------------------------------------------------------------------------------ Layer 5
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

;------------------------------------------------------------------------------ Layer 6
;; Application lifecycle

(defn start!
  "Start the TUI application.
   1. Enter alternate screen
   2. Initial render
   3. Start input polling thread
   4. Register subscriptions

   The model-ref watcher (installed in create-app) handles all subsequent
   renders triggered by dispatch!.

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
   1. Remove render watcher
   2. Unregister subscriptions
   3. Stop input thread
   4. Interrupt in-flight side-effect threads
   5. Exit alternate screen

   Safe to call multiple times."
  [app]
  (when (:running? @app)
    (swap! app assoc :running? false)
    ;; Remove render watcher to stop rendering
    (remove-watch (:model-ref @app) ::render)
    ;; Unsubscribe
    (when-let [unsub (:unsub-fn @app)]
      (try (unsub) (catch Exception _)))
    ;; Stop input thread
    (when-let [thread (:input-thread @app)]
      (.interrupt thread))
    ;; Interrupt all in-flight side-effect threads
    (when-let [threads-atom (:effect-threads @app)]
      (doseq [^Thread t @threads-atom]
        (try (.interrupt t) (catch Exception _)))
      (reset! threads-atom #{}))
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
