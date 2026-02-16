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

(ns ai.miniforge.tui-views.interface
  "Public API for the TUI views component.

   Provides the top-level entry points to start and stop the miniforge TUI.
   Wires together the tui-engine (rendering) with domain data (event stream)."
  (:require
   [ai.miniforge.tui-engine.interface :as tui]
   [ai.miniforge.tui-views.model :as model]
   [ai.miniforge.tui-views.update :as update]
   [ai.miniforge.tui-views.view :as view]
   [ai.miniforge.tui-views.subscription :as subscription]
   [ai.miniforge.tui-views.persistence :as persistence]))

;------------------------------------------------------------------------------ Layer 0
;; TUI lifecycle

(defn start-tui!
  "Start the miniforge TUI.

   Arguments:
   - event-stream - Event stream atom from event-stream/create-event-stream
   - opts         - {:throttle-ms 1000, :screen screen} optional overrides

   Returns: app atom. Call stop-tui! to shut down.

   The TUI will:
   1. Enter alternate screen mode
   2. Subscribe to the event stream
   3. Start the input polling loop
   4. Render the workflow list view

   The TUI blocks the calling thread until the user quits (q key)."
  [event-stream & [{:keys [throttle-ms screen load-limit]
                    :or {throttle-ms 1000 load-limit 100}}]]
  (let [app (tui/create-app
             {:init   (fn []
                        (persistence/load-all-into-model
                         (model/init-model)
                         {:limit load-limit}))
              :update update/update-model
              :view   view/root-view
              :screen screen
              :effect-handler
              (fn [effect]
                (case (:type effect)
                  :sync-prs
                  (let [prs (persistence/load-pr-items)]
                    [:msg/prs-synced {:pr-items prs}])

                  :discover-repos
                  (let [result (persistence/discover-repos (:owner effect))]
                    [:msg/repos-discovered result])

                  :browse-repos
                  (let [result (persistence/browse-repos {:owner (:owner effect)
                                                          :provider (:provider effect)
                                                          :limit (:limit effect)})]
                    [:msg/repos-browsed (assoc result :source (:source effect))])

                  ;; Unknown effect type — no-op
                  nil))
              :subscriptions
              (when event-stream
                (fn [dispatch-fn]
                  (subscription/subscribe-to-stream!
                   event-stream dispatch-fn
                   {:throttle-ms throttle-ms})))})]
    (tui/start! app)
    ;; Block until quit
    (try
      (while (not (:quit? (tui/get-model app)))
        (Thread/sleep 100))
      (finally
        (tui/stop! app)))
    app))

(defn stop-tui!
  "Stop the miniforge TUI. Restores terminal state.
   Safe to call multiple times."
  [app]
  (tui/stop! app))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Start TUI with event stream
  (require '[ai.miniforge.event-stream.interface :as es])
  (def stream (es/create-event-stream))
  (def app (future (start-tui! stream)))

  ;; Send test events
  (es/publish! stream (es/workflow-started stream (random-uuid) {:name "test-wf"}))

  ;; Stop
  (stop-tui! @app)

  :leave-this-here)
