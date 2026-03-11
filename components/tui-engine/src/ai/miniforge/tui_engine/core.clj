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
  "Rendering core for the TUI engine.

   Handles rendering primitives, app state creation, and the public
   render entry point. Runtime concerns (side-effects, dispatch,
   input polling, lifecycle) live in ai.miniforge.tui-engine.runtime.

   Concurrency model:
   - model and prev-buffer are refs, coordinated via dosync
   - add-watch on model-ref triggers rendering after every state transition
   - Rendering is serialized via a lock to prevent concurrent screen writes"
  (:require
   [ai.miniforge.tui-engine.screen :as screen]
   [ai.miniforge.tui-engine.layout :as layout]))

;------------------------------------------------------------------------------ Layer 0
;; Rendering primitives

(defn- flush-run!
  "Write a batched run of same-style characters to screen."
  [screen ^StringBuilder sb start-col row fg bg bold?]
  (when (pos? (.length sb))
    (screen/put-string! screen start-col row (.toString sb) fg bg bold?)
    (.setLength sb 0)))

(defn- paint-row!
  "Diff a single row between old and new buffers, writing changed cells to screen.
   Batches consecutive same-style cells into single put-string! calls."
  [screen ^StringBuilder sb row new-row old-row max-col resized?]
  (loop [col 0
         run-col 0
         run-fg nil
         run-bg nil
         run-bold? false]
    (if (< col max-col)
      (let [new-cell (nth new-row col)
            old-cell (when old-row (nth old-row col nil))
            changed? (or resized? (not= new-cell old-cell))]
        (if changed?
          (let [{:keys [char fg bg bold?]} new-cell
                same-style? (and (pos? (.length sb))
                                 (= fg run-fg) (= bg run-bg)
                                 (= (boolean bold?) run-bold?))]
            (if same-style?
              (do (.append sb (or char \space))
                  (recur (inc col) run-col run-fg run-bg run-bold?))
              (do (flush-run! screen sb run-col row run-fg run-bg run-bold?)
                  (.append sb (or char \space))
                  (recur (inc col) col fg bg (boolean bold?)))))
          ;; Not changed — flush any pending run
          (do (flush-run! screen sb run-col row run-fg run-bg run-bold?)
              (recur (inc col) (inc col) nil nil false))))
      ;; End of row — flush remaining
      (flush-run! screen sb run-col row run-fg run-bg run-bold?))))

(defn paint-screen!
  "Impure: diff new-buffer against prev-buffer and write changed cells to screen.
   Must be called under the render lock to prevent concurrent screen writes.
   Batches consecutive same-style cells into single put-string! calls.
   Returns new-buffer (to be stored as prev-buffer)."
  [screen prev-buffer new-buffer]
  (let [buf-rows (count new-buffer)
        buf-cols (if (pos? buf-rows) (count (first new-buffer)) 0)
        [scr-cols scr-rows] (screen/get-size screen)
        resized? (or (nil? prev-buffer)
                     (not= buf-rows (count prev-buffer))
                     (and (pos? buf-rows) (seq prev-buffer)
                          (not= buf-cols (count (first prev-buffer)))))]
    (when resized? (screen/clear! screen))
    (let [sb (StringBuilder. 64)]
      (doseq [row (range (min scr-rows buf-rows))]
        (let [new-row (nth new-buffer row)
              old-row (when-not resized? (nth prev-buffer row nil))
              max-col (min scr-cols (count new-row))]
          (paint-row! screen sb row new-row old-row max-col resized?))))
    (screen/refresh! screen)
    new-buffer))

(defn do-render!
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

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Minimal rendering example (see runtime.clj for full app lifecycle)
  (def sample-app
    (create-app
     {:init   (fn [] {:count 0})
      :update (fn [model _msg] model)
      :view   (fn [model [cols rows]]
                (layout/text [cols rows]
                             (str "Count: " (:count model))))}))

  ;; Render manually for testing
  (render! @sample-app)

  :leave-this-here)
