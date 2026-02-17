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
   [clojure.java.browse :as browse]
   [ai.miniforge.tui-engine.interface :as tui]
   [ai.miniforge.tui-views.model :as model]
   [ai.miniforge.tui-views.update :as update]
   [ai.miniforge.tui-views.view :as view]
   [ai.miniforge.tui-views.subscription :as subscription]
   [ai.miniforge.tui-views.persistence :as persistence]
   [ai.miniforge.policy-pack.interface :as policy-pack]
   [ai.miniforge.pr-train.interface :as pr-train]))

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
  (let [train-mgr (pr-train/create-manager)
        app (tui/create-app
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
                  (let [result (persistence/browse-repos
                                {:owner (:owner effect)
                                 :provider (:provider effect)
                                 :limit (:limit effect)})]
                    [:msg/repos-browsed (assoc result :source (:source effect))])

                  :open-url
                  (do (when-let [url (:url effect)]
                        (try
                          (browse/browse-url url)
                          (catch Exception _ nil)))
                      nil)

                  :evaluate-policy
                  (try
                    (let [packs (persistence/load-policy-packs)
                          pr-data (:pr effect)
                          pr-id (:pr-id effect)
                          result (policy-pack/evaluate-external-pr
                                  packs pr-data)]
                      [:msg/policy-evaluated {:pr-id pr-id :result result}])
                    (catch Exception e
                      [:msg/policy-evaluated {:pr-id (:pr-id effect)
                                              :result {:evaluation/passed? nil
                                                       :evaluation/error (.getMessage e)}}]))

                  ;; Train side-effects
                  :create-train
                  (try
                    (let [dag-id (java.util.UUID/randomUUID)
                          train-id (pr-train/create-train train-mgr (:name effect) dag-id
                                                          (or (:description effect) ""))]
                      [:msg/train-created {:train-id train-id :train-name (:name effect)}])
                    (catch Exception e
                      [:msg/side-effect-error {:type :create-train :error (.getMessage e)}]))

                  :add-to-train
                  (try
                    (let [train-id (:train-id effect)
                          prs (:prs effect)]
                      (doseq [pr prs]
                        (pr-train/add-pr train-mgr train-id
                                         (:pr/repo pr)
                                         (:pr/number pr)
                                         (or (:pr/url pr) "")
                                         (or (:pr/branch pr) "")
                                         (or (:pr/title pr) "")))
                      (let [train (pr-train/get-train train-mgr train-id)]
                        [:msg/prs-added-to-train {:train train :added (count prs)}]))
                    (catch Exception e
                      [:msg/side-effect-error {:type :add-to-train :error (.getMessage e)}]))

                  :merge-next
                  (try
                    (let [train-id (:train-id effect)
                          result (pr-train/merge-next train-mgr train-id)]
                      (if result
                        [:msg/merge-started {:pr-number (:pr-number result)
                                             :train (:train result)}]
                        [:msg/side-effect-error {:type :merge-next
                                                 :error "No PRs ready to merge"}]))
                    (catch Exception e
                      [:msg/side-effect-error {:type :merge-next :error (.getMessage e)}]))

                  ;; Batch action side-effects
                  :review-prs
                  (try
                    (let [packs (persistence/load-policy-packs)
                          results (mapv (fn [pr]
                                          (let [pr-id [(:pr/repo pr) (:pr/number pr)]
                                                result (try
                                                         (policy-pack/evaluate-external-pr packs pr)
                                                         (catch Exception e
                                                           {:evaluation/passed? nil
                                                            :evaluation/error (.getMessage e)}))]
                                            {:pr-id pr-id :result result}))
                                        (:prs effect))]
                      [:msg/review-completed {:results results}])
                    (catch Exception e
                      [:msg/side-effect-error {:type :review-prs :error (.getMessage e)}]))

                  :remediate-prs
                  ;; Placeholder — remediation goes through pr-lifecycle
                  (let [prs (:prs effect)
                        fixable (count (filter #(seq (get-in % [:pr/policy :evaluation/violations])) prs))]
                    [:msg/remediation-completed {:fixed 0 :failed fixable
                                                :message "Remediation via pr-lifecycle not yet wired"}])

                  :decompose-pr
                  ;; Placeholder — decomposition analysis
                  (let [pr (:pr effect)
                        pr-id [(:pr/repo pr) (:pr/number pr)]]
                    [:msg/decomposition-started {:pr-id pr-id
                                                :plan {:sub-prs []
                                                       :message "Decomposition analysis not yet wired"}}])

                  ;; Chat side-effects
                  :chat-send
                  ;; v1: Placeholder — orchestrator workflow dispatch
                  ;; Full implementation routes through orchestrator/execute-workflow
                  ;; with chat context, PR data, and conversation history.
                  (let [msg (:message effect)
                        ctx (:context effect)
                        ctx-type (:type ctx)]
                    (try
                      [:msg/chat-response
                       {:content (str "Chat integration pending orchestrator wiring.\n\n"
                                      "Context: " (name (or ctx-type :unknown)) "\n"
                                      "Your message: " msg "\n\n"
                                      (case ctx-type
                                        :pr-detail
                                        (let [pr (:pr ctx)]
                                          (str "PR: " (:pr/repo pr) "#" (:pr/number pr)
                                               " — " (:pr/title pr) "\n"
                                               "I can help analyze this PR's risk, readiness, "
                                               "and policy compliance once orchestrator is connected."))
                                        :pr-fleet
                                        (let [n (count (:selected-prs ctx))]
                                          (str (if (pos? n)
                                                 (str n " PR(s) selected for discussion.")
                                                 "No PRs selected. Select PRs with Space first.")
                                               "\nI can help steer these PRs through review, "
                                               "remediation, and merge once orchestrator is connected."))
                                        "Chat context not recognized."))
                        :actions []}]
                      (catch Exception e
                        [:msg/chat-response
                         {:content (str "Error: " (.getMessage e))
                          :actions []}])))

                  :chat-execute-action
                  ;; Placeholder — execute chat-suggested action through command path
                  [:msg/chat-action-result {:success? false
                                            :message "Chat action execution not yet wired"}]

                  ;; Unknown effect — no-op
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
