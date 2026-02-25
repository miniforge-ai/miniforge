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
   [ai.miniforge.tui-views.msg :as msg]
   [ai.miniforge.tui-views.update :as update]
   [ai.miniforge.tui-views.view :as view]
   [ai.miniforge.tui-views.subscription :as subscription]
   [ai.miniforge.tui-views.persistence :as persistence]
   [ai.miniforge.response.interface :as response]
   [ai.miniforge.policy-pack.interface :as policy-pack]
   [ai.miniforge.pr-train.interface :as pr-train]))

;------------------------------------------------------------------------------ Layer 0
;; Side-effect handlers — each returns [msg-type payload] or nil

(defn- handle-sync-prs [{:keys [state]}]
  (msg/prs-synced (persistence/load-pr-items (when state {:state state}))))

(defn- handle-discover-repos [{:keys [owner]}]
  (msg/repos-discovered (persistence/discover-repos owner)))

(defn- handle-browse-repos [{:keys [owner provider limit source]}]
  (let [result (persistence/browse-repos {:owner owner :provider provider :limit limit})]
    (msg/repos-browsed (assoc result :source source))))

(defn- handle-open-url [{:keys [url]}]
  (when url
    (try (browse/browse-url url) (catch Exception _ nil)))
  nil)

(defn- handle-evaluate-policy [{:keys [pr pr-id]}]
  (try
    (let [result (policy-pack/evaluate-external-pr
                  (persistence/load-policy-packs) pr)]
      (msg/policy-evaluated pr-id result))
    (catch Exception e
      (msg/policy-evaluated pr-id
                            {:evaluation/passed? nil
                             :evaluation/error (.getMessage e)}))))

(defn- handle-create-train [train-mgr {:keys [name description]}]
  (try
    (let [train-id (pr-train/create-train
                    train-mgr name
                    (java.util.UUID/randomUUID) (or description ""))]
      (msg/train-created train-id name))
    (catch Exception e
      (msg/side-effect-error
       (response/error (.getMessage e) {:data {:type :create-train}})))))

(defn- handle-add-to-train [train-mgr effect]
  (try
    (let [{:keys [train-id prs]} effect]
      (doseq [pr prs]
        (pr-train/add-pr train-mgr train-id
                         (:pr/repo pr) (:pr/number pr)
                         (or (:pr/url pr) "") (or (:pr/branch pr) "")
                         (or (:pr/title pr) "")))
      (msg/prs-added-to-train (pr-train/get-train train-mgr train-id)
                               (count prs)))
    (catch Exception e
      (msg/side-effect-error
       (response/error (.getMessage e) {:data {:type :add-to-train}})))))

(defn- handle-merge-next [train-mgr {:keys [train-id]}]
  (try
    (let [result (pr-train/merge-next train-mgr train-id)]
      (if result
        (msg/merge-started (:pr-number result) (:train result))
        (msg/side-effect-error
         (response/error "No PRs ready to merge" {:data {:type :merge-next}}))))
    (catch Exception e
      (msg/side-effect-error
       (response/error (.getMessage e) {:data {:type :merge-next}})))))

(defn- handle-review-prs [{:keys [prs]}]
  (try
    (let [packs (persistence/load-policy-packs)]
      (msg/review-completed
       (mapv (fn [pr]
               {:pr-id  [(:pr/repo pr) (:pr/number pr)]
                :result (try
                          (policy-pack/evaluate-external-pr packs pr)
                          (catch Exception e
                            {:evaluation/passed? nil
                             :evaluation/error (.getMessage e)}))})
             prs)))
    (catch Exception e
      (msg/side-effect-error
       (response/error (.getMessage e) {:data {:type :review-prs}})))))

(defn- handle-remediate-prs [{:keys [prs]}]
  (let [fixable (count (filter #(seq (get-in % [:pr/policy :evaluation/violations])) prs))]
    (msg/remediation-completed 0 fixable "Remediation via pr-lifecycle not yet wired")))

(defn- handle-decompose-pr [{:keys [pr]}]
  (msg/decomposition-started [(:pr/repo pr) (:pr/number pr)]
                             {:sub-prs []
                              :message "Decomposition analysis not yet wired"}))

(defn- handle-chat-send [{:keys [message context]}]
  (try
    (let [ctx-type (:type context)]
      (msg/chat-response
       (str "Chat integration pending orchestrator wiring.\n\n"
            "Context: " (name (or ctx-type :unknown)) "\n"
            "Your message: " message "\n\n"
            (case ctx-type
              :pr-detail (let [pr (:pr context)]
                           (str "PR: " (:pr/repo pr) "#" (:pr/number pr)
                                " — " (:pr/title pr) "\n"
                                "I can help analyze this PR's risk, readiness, "
                                "and policy compliance once orchestrator is connected."))
              :pr-fleet  (let [n (count (:selected-prs context))]
                           (str (if (pos? n)
                                  (str n " PR(s) selected for discussion.")
                                  "No PRs selected. Select PRs with Space first.")
                                "\nI can help steer these PRs through review, "
                                "remediation, and merge once orchestrator is connected."))
              "Chat context not recognized."))
       []))
    (catch Exception e
      (msg/chat-response (str "Error: " (.getMessage e)) []))))

;------------------------------------------------------------------------------ Layer 1
;; Effect dispatcher

(defn- dispatch-effect
  "Route a side-effect to its handler. Returns [msg-type payload] or nil."
  [train-mgr effect]
  (case (:type effect)
    :sync-prs          (handle-sync-prs effect)
    :discover-repos    (handle-discover-repos effect)
    :browse-repos      (handle-browse-repos effect)
    :open-url          (handle-open-url effect)
    :evaluate-policy   (handle-evaluate-policy effect)
    :create-train      (handle-create-train train-mgr effect)
    :add-to-train      (handle-add-to-train train-mgr effect)
    :merge-next        (handle-merge-next train-mgr effect)
    :review-prs        (handle-review-prs effect)
    :remediate-prs     (handle-remediate-prs effect)
    :decompose-pr      (handle-decompose-pr effect)
    :chat-send         (handle-chat-send effect)
    :chat-execute-action (msg/chat-action-result
                          (response/failure "Chat action execution not yet wired" {}))
    nil))

;------------------------------------------------------------------------------ Layer 2
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
              :effect-handler (partial dispatch-effect train-mgr)
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
