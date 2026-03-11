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

(ns ai.miniforge.tui-engine.runtime
  "Elm-architecture runtime: side-effects, message dispatch, input polling,
   and application lifecycle.

   Split from core.clj to keep each namespace within the 3-layer limit.
   Core handles rendering (layers 0-2); this namespace handles runtime
   concerns (layers 0-2, renumbered from the original 3-6)."
  (:require
   [ai.miniforge.tui-engine.core :as core]
   [ai.miniforge.tui-engine.screen :as screen]
   [ai.miniforge.tui-engine.input :as input]))

;------------------------------------------------------------------------------ Layer 0
;; Side-effect execution

(defn execute-side-effect!
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

;------------------------------------------------------------------------------ Layer 1
;; Message dispatch and input polling

(defn dispatch!
  "Dispatch a message into the Elm update loop.

   Uses dosync to atomically update the model ref. The add-watch
   installed in create-app triggers rendering after each state transition.
   dispatch! only does the pure model update -- no screen I/O.

   Side-effect protocol:
   If the new model contains a :side-effect key, it is stripped from the model
   and executed asynchronously via the registered :effect-handler. The handler
   runs on a background thread and its result message is dispatched back into
   the update loop."
  [app msg]
  (let [{:keys [model-ref update-fn]} @app
        pending-effect (atom nil)]
    ;; Atomic model update -- watcher fires after commit to handle rendering
    (dosync
     (let [new-model (update-fn @model-ref msg)
           fx (:side-effect new-model)
           fxs (:side-effects new-model)
           all-fx (cond-> [] fx (conj fx) fxs (into fxs))
           clean-model (dissoc new-model :side-effect :side-effects)]
       (when (seq all-fx) (reset! pending-effect all-fx))
       (ref-set model-ref clean-model)))
    ;; Side-effects executed outside the transaction
    (when-let [fxs @pending-effect]
      (when-let [handler (:effect-handler @app)]
        (let [dispatch-fn (fn [result-msg] (dispatch! app result-msg))]
          (doseq [fx fxs]
            (execute-side-effect! fx handler dispatch-fn
                                  (:effect-threads @app))))))))

(defn get-model
  "Get current model snapshot."
  [app]
  @(:model-ref @app))

(defn start-input-thread!
  "Start a daemon thread that polls for keyboard input and dispatches messages.
   Catches exceptions per-keystroke so a single dispatch failure doesn't kill
   the input thread (which would freeze the TUI)."
  [app]
  (let [thread (Thread.
                (fn []
                  (try
                    (while (:running? @app)
                      (try
                        (let [screen (:screen @app)
                              key (input/poll-key screen)]
                          (if key
                            (dispatch! app [:input key])
                            (Thread/sleep 16))) ; ~60Hz polling
                        (catch InterruptedException e (throw e))
                        (catch Exception _)))
                    (catch InterruptedException _))))]
    (.setDaemon thread true)
    (.setName thread "tui-input-poll")
    (.start thread)
    thread))

(defn- check-resize-and-tick!
  "Check for terminal size changes or pending-chat ticks and re-render if needed."
  [app last-size last-tick]
  (try
    (let [screen (:screen @app)
          size (screen/get-size screen)
          model @(:model-ref @app)
          resized? (and size (not= size @last-size))
          pending? (get-in model [:chat :pending?])
          now-sec (quot (System/currentTimeMillis) 1000)
          tick? (and pending? (not= now-sec @last-tick))]
      (when resized?
        (reset! last-size size))
      (when tick?
        (reset! last-tick now-sec))
      (when (or resized? tick?)
        (core/do-render! @app model)))
    (catch Exception _)))

(defn start-resize-thread!
  "Start a scheduled executor that checks for terminal size changes and forces
   a re-render when the size changes. Also re-renders periodically while
   the chat agent is thinking (to update the spinner/elapsed timer).
   Checks every 250ms using a ScheduledExecutorService."
  [app]
  (let [last-size (atom nil)
        last-tick (atom 0)
        ^java.util.concurrent.ScheduledExecutorService
        executor (java.util.concurrent.Executors/newSingleThreadScheduledExecutor
                  (reify java.util.concurrent.ThreadFactory
                    (newThread [_ r]
                      (doto (Thread. r "tui-resize-check")
                        (.setDaemon true)))))]
    (.scheduleAtFixedRate executor
                         (fn [] (check-resize-and-tick! app last-size last-tick))
                         250 250 java.util.concurrent.TimeUnit/MILLISECONDS)
    executor))

;------------------------------------------------------------------------------ Layer 2
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
    (swap! app core/render!)
    ;; Start input thread
    (let [thread (start-input-thread! app)]
      (swap! app assoc :input-thread thread))
    ;; Start resize detection executor
    (let [executor (start-resize-thread! app)]
      (swap! app assoc :resize-executor executor))
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
    (remove-watch (:model-ref @app) :ai.miniforge.tui-engine.core/render)
    ;; Unsubscribe
    (when-let [unsub (:unsub-fn @app)]
      (try (unsub) (catch Exception _)))
    ;; Stop input thread
    (when-let [thread (:input-thread @app)]
      (.interrupt thread))
    ;; Stop resize executor
    (when-let [^java.util.concurrent.ScheduledExecutorService executor (:resize-executor @app)]
      (.shutdownNow executor))
    ;; Interrupt all in-flight side-effect threads
    (when-let [threads-atom (:effect-threads @app)]
      (doseq [^Thread t @threads-atom]
        (try (.interrupt t) (catch Exception _)))
      (reset! threads-atom #{}))
    ;; Restore terminal
    (try
      (screen/stop-screen! (:screen @app))
      (catch Exception _))
    (swap! app assoc :unsub-fn nil :input-thread nil :resize-executor nil)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (require '[ai.miniforge.tui-engine.layout :as layout])

  ;; Minimal app example
  (def my-app
    (core/create-app
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
