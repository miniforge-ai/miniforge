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
   [clojure.java.io :as io]
   [clojure.string :as str]
   [ai.miniforge.tui-engine.interface :as tui]
   [ai.miniforge.tui-views.model :as model]
   [ai.miniforge.tui-views.msg :as msg]
   [ai.miniforge.tui-views.update :as update]
   [ai.miniforge.tui-views.view :as view]
   [ai.miniforge.tui-views.subscription :as subscription]
   [ai.miniforge.tui-views.file-subscription :as file-subscription]
   [ai.miniforge.tui-views.persistence :as persistence]
   [ai.miniforge.response.interface :as response]
   [ai.miniforge.policy-pack.interface :as policy-pack]
   [ai.miniforge.pr-train.interface :as pr-train]
   [ai.miniforge.llm.interface :as llm]))

;------------------------------------------------------------------------------ Layer 0
;; Side-effect handlers — each returns [msg-type payload] or nil

(defn handle-sync-prs [{:keys [state]}]
  (msg/prs-synced (persistence/load-pr-items (when state {:state state}))))

(defn handle-discover-repos [{:keys [owner]}]
  (msg/repos-discovered (persistence/discover-repos owner)))

(defn handle-browse-repos [{:keys [owner provider limit source]}]
  (let [result (persistence/browse-repos {:owner owner :provider provider :limit limit})]
    (msg/repos-browsed (assoc result :source source))))

(defn handle-open-url [{:keys [url]}]
  (when url
    (try (browse/browse-url url) (catch Exception _ nil)))
  nil)

(defn handle-evaluate-policy [{:keys [pr pr-id]}]
  (try
    (let [result (policy-pack/evaluate-external-pr
                  (persistence/load-policy-packs) pr)]
      (msg/policy-evaluated pr-id result))
    (catch Exception e
      (msg/policy-evaluated pr-id
                            {:evaluation/passed? nil
                             :evaluation/error (.getMessage e)}))))

(defn handle-create-train [train-mgr {:keys [name description]
                                       :or {description ""}}]
  (try
    (let [train-id (pr-train/create-train
                    train-mgr name
                    (java.util.UUID/randomUUID) description)]
      (msg/train-created train-id name))
    (catch Exception e
      (msg/side-effect-error
       (response/error (.getMessage e) {:data {:type :create-train}})))))

(defn handle-add-to-train [train-mgr {:keys [train-id prs]}]
  (try
    (doseq [pr prs]
      (pr-train/add-pr train-mgr train-id
                       (:pr/repo pr) (:pr/number pr)
                       (get pr :pr/url "") (get pr :pr/branch "")
                       (get pr :pr/title "")))
    (msg/prs-added-to-train (pr-train/get-train train-mgr train-id)
                             (count prs))
    (catch Exception e
      (msg/side-effect-error
       (response/error (.getMessage e) {:data {:type :add-to-train}})))))

(defn handle-merge-next [train-mgr {:keys [train-id]}]
  (try
    (let [result (pr-train/merge-next train-mgr train-id)]
      (if result
        (msg/merge-started (:pr-number result) (:train result))
        (msg/side-effect-error
         (response/error "No PRs ready to merge" {:data {:type :merge-next}}))))
    (catch Exception e
      (msg/side-effect-error
       (response/error (.getMessage e) {:data {:type :merge-next}})))))

(defn handle-review-prs [{:keys [prs]}]
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

(defn handle-remediate-prs [{:keys [prs]}]
  (let [fixable (count (filter #(seq (get-in % [:pr/policy :evaluation/violations])) prs))]
    (msg/remediation-completed 0 fixable "Remediation via pr-lifecycle not yet wired")))

(defn handle-decompose-pr [{:keys [pr]}]
  (msg/decomposition-started [(:pr/repo pr) (:pr/number pr)]
                             {:sub-prs []
                              :message "Decomposition analysis not yet wired"}))

(defn handle-control-action [{:keys [action workflow-id]}]
  (let [commands-dir (io/file (System/getProperty "user.home")
                              ".miniforge" "commands" (str workflow-id))
        cmd-file (io/file commands-dir (str (System/currentTimeMillis) ".edn"))]
    (.mkdirs commands-dir)
    (spit cmd-file (pr-str {:command action :timestamp (java.util.Date.)}))
    nil))

;; Lazy LLM client — initialized on first chat message
(def llm-client (delay (llm/create-client)))

(defn format-check-context [checks]
  (str/join ", " (map #(str (:name %) "=" (-> % (get :conclusion :unknown) name)) checks)))

(defn build-pr-context-str
  "Build a text summary of PR data for the LLM system prompt."
  [{:keys [pr/behind-main? pr/branch pr/ci-status pr/number
           pr/policy pr/readiness pr/repo pr/risk pr/status pr/title]
    :or   {ci-status :unknown status :unknown}
    :as   pr}]
  (when pr
    (let [checks                                     (get pr :pr/ci-checks [])
          {:keys [readiness/score readiness/ready?]}  readiness
          {:keys [evaluation/passed?
                  evaluation/packs-applied]}          policy
          risk-level                                  (get risk :risk/level :unknown)
          risk-score                                  (:risk/score risk)
          ci-str                                      (if (seq checks)
                                                        (str "CI checks: " (format-check-context checks))
                                                        (str "CI status: " (name ci-status)))]
      (str "PR: " repo "#" number " — " title "\n"
           "Branch: " branch "\n"
           "Status: " (name status) "\n"
           ci-str "\n"
           "Behind main: " (if behind-main? "yes" "no") "\n"
           (when readiness
             (str "Readiness score: " score
                  (when ready? " (ready)")
                  "\n"))
           (when risk
             (str "Risk level: " (name risk-level)
                  (when risk-score
                    (str " (score: " (format "%.2f" (double risk-score)) ")"))
                  "\n"))
           (when policy
             (str "Policy: " (if passed? "passed" "FAILED")
                  (when-let [packs packs-applied]
                    (str " (packs: " (str/join ", " packs) ")"))
                  "\n"))))))

(defn build-chat-system-prompt
  "Build a context-aware system prompt for the chat LLM."
  [context]
  (let [ctx-type (get context :type :unknown)]
    (str "You are a PR fleet analyst for Miniforge, an agentic SDLC platform.\n"
         "Help the user understand and manage their pull requests.\n"
         "Be concise and actionable. Reference specific data when possible.\n\n"
         (case ctx-type
           :pr-detail
           (str "The user is viewing a specific PR:\n"
                (build-pr-context-str (:pr context)))

           :pr-fleet
           (let [prs (:selected-prs context)
                 n (count prs)]
             (str "The user is in the PR fleet view.\n"
                  "Total PRs: " (:total-prs context 0) "\n"
                  "Active filter: " (name (get context :active-filter :open)) "\n"
                  (if (pos? n)
                    (str n " PR(s) selected:\n"
                         (str/join "\n"
                           (map #(str "- " (:pr/repo %) "#" (:pr/number %)
                                      " " (:pr/title %))
                                prs)))
                    "No PRs currently selected.")))

           "Unknown context."))))

(defn handle-chat-send [{:keys [message context history]}]
  (try
    (let [system-prompt (build-chat-system-prompt context)
          messages (mapv (fn [m]
                           {:role (name (:role m))
                            :content (:content m)})
                         (or history []))
          request (cond-> {:system system-prompt}
                    (seq messages) (assoc :messages
                                         (conj messages
                                               {:role "user" :content message}))
                    (empty? messages) (assoc :prompt message))
          result (llm/complete @llm-client request)]
      (if (llm/success? result)
        (msg/chat-response (llm/get-content result) [])
        (msg/chat-response
         (str "LLM error: " (get-in (llm/get-error result) [:message] "Unknown error"))
         [])))
    (catch Exception e
      (msg/chat-response (str "Chat error: " (.getMessage e)) []))))

;------------------------------------------------------------------------------ Layer 1
;; Effect dispatcher

(defn dispatch-effect
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
    :control-action    (handle-control-action effect)
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

(defn start-standalone-tui!
  "Start the TUI in standalone monitoring mode.
   Discovers and tail-follows workflow event files from ~/.miniforge/events/.
   Does not require an in-memory event stream."
  [& [opts]]
  (let [train-mgr (pr-train/create-manager)
        app (tui/create-app
             {:init   (fn []
                        (persistence/load-all-into-model
                         (model/init-model)
                         {:limit (:load-limit opts 100)}))
              :update update/update-model
              :view   view/root-view
              :screen (:screen opts)
              :effect-handler (partial dispatch-effect train-mgr)
              :subscriptions
              (fn [dispatch-fn]
                (file-subscription/subscribe-to-files!
                 dispatch-fn
                 {:poll-ms (:poll-ms opts 500)
                  :scan-ms (:scan-ms opts 2000)
                  :hydrate-existing? false}))})]
    (tui/start! app)
    (try
      (while (not (:quit? (tui/get-model app)))
        (Thread/sleep 100))
      (finally
        (tui/stop! app)))
    app))

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
