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
   [ai.miniforge.tui-views.persistence.pr :as persistence-pr]
   [ai.miniforge.tui-views.persistence.pr-cache :as pr-cache]
   [ai.miniforge.response.interface :as response]
   [ai.miniforge.policy-pack.interface :as policy-pack]
   [ai.miniforge.pr-train.interface :as pr-train]
   [ai.miniforge.llm.interface :as llm]
   [ai.miniforge.tui-views.persistence.github :as github]
   [ai.miniforge.tui-views.prompts :as prompts]))

;------------------------------------------------------------------------------ Layer 0
;; Side-effect handlers — each returns [msg-type payload] or nil

(defn handle-sync-prs [{:keys [state]}]
  (let [{:keys [prs error]} (persistence-pr/load-pr-items (when state {:state state}))
        cache (pr-cache/read-cache)
        prs-with-cache (pr-cache/apply-cached-policy (or prs []) cache)
        cached-risk (pr-cache/apply-cached-agent-risk (or prs []) cache)]
    (msg/prs-synced-with-cache prs-with-cache cached-risk error)))

(defn handle-discover-repos [{:keys [owner]}]
  (msg/repos-discovered (persistence-pr/discover-repos owner)))

(defn handle-browse-repos [{:keys [owner provider limit source]}]
  (let [result (persistence-pr/browse-repos {:owner owner :provider provider :limit limit})]
    (msg/repos-browsed (assoc result :source source))))

(defn handle-open-url [{:keys [url]}]
  (when url
    (try (browse/browse-url url) (catch Exception _ nil)))
  nil)

(defn handle-evaluate-policy [{:keys [pr pr-id]}]
  (try
    (let [result (policy-pack/evaluate-external-pr
                  (persistence-pr/load-policy-packs) pr)]
      (msg/policy-evaluated pr-id result))
    (catch Exception e
      (msg/policy-evaluated pr-id
                            {:evaluation/passed? nil
                             :evaluation/error (.getMessage e)}))))

(defn handle-batch-evaluate-policy
  "Evaluate policy for multiple PRs in batch.
   Returns a single :msg/review-completed message with all results,
   reusing the existing review-completed handler to merge policy data."
  [{:keys [prs]}]
  (try
    (let [packs (persistence-pr/load-policy-packs)]
      (msg/review-completed
       (mapv (fn [pr]
               {:pr-id  [(:pr/repo pr) (:pr/number pr)]
                :result (try
                          (policy-pack/evaluate-external-pr packs pr)
                          (catch Exception e
                            {:evaluation/passed? nil
                             :evaluation/error (.getMessage e)}))})
             prs)))
    (catch Exception _e
      (msg/review-completed []))))

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
    (let [packs (persistence-pr/load-policy-packs)]
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

(defonce ^:private decompose-llm-client (delay (llm/create-client)))

(defn handle-decompose-pr
  "Decompose a large PR into sub-PRs.
   Entire body is wrapped in try/catch — no exceptions leak."
  [{:keys [pr]}]
  (let [pr-id [(:pr/repo pr) (:pr/number pr)]]
    (try
      (let [{:keys [diff detail]} (github/fetch-pr-diff-and-detail
                                   (:pr/repo pr) (:pr/number pr))
            changed-files (mapv :path (:files detail []))
            decompose-fn (try (requiring-resolve
                               'ai.miniforge.pr-decompose.interface/decompose)
                              (catch Throwable _ nil))]
        (cond
          ;; Both fetches failed
          (and (nil? diff) (nil? detail))
          (msg/decomposition-started pr-id
                                     {:sub-prs []
                                      :message "Failed to fetch PR diff and details"})

          ;; Decompose component not available
          (nil? decompose-fn)
          (msg/decomposition-started pr-id
                                     {:sub-prs []
                                      :message "Decomposition component not available"})

          :else
          (let [llm-fn (fn [req] (llm/complete @decompose-llm-client req))
                result (decompose-fn pr (or diff "") changed-files llm-fn)]
            (if (:ok? result)
              (let [plan (get-in result [:data :plan])]
                (msg/decomposition-started pr-id
                                           {:sub-prs (:sub-prs plan [])
                                            :strategy (:strategy plan)
                                            :original-size (:original-size plan)
                                            :coverage (:coverage plan)}))
              (msg/decomposition-started pr-id
                                         {:sub-prs []
                                          :message (or (get-in result [:error :message])
                                                       "Decomposition failed")})))))
      (catch Throwable e
        (msg/decomposition-started pr-id
                                   {:sub-prs []
                                    :message (.getMessage e)})))))

(defn handle-fetch-pr-diff
  "Fetch PR diff and detail from GitHub CLI."
  [{:keys [repo number]}]
  (try
    (let [n (long (if (string? number) (Long/parseLong number) number))
          {:keys [diff detail]} (github/fetch-pr-diff-and-detail repo n)]
      (if (and (nil? diff) (nil? detail))
        (msg/pr-diff-fetched [repo n] nil nil "Failed to fetch PR diff and details")
        (msg/pr-diff-fetched [repo n] diff detail nil)))
    (catch Throwable e
      (msg/pr-diff-fetched [repo number] nil nil (.getMessage e)))))

(defn handle-cache-policy-result [{:keys [pr-id result prs]}]
  (pr-cache/persist-policy-result! pr-id result prs)
  nil)

(defn handle-cache-risk-triage [{:keys [risk-map prs]}]
  (pr-cache/persist-risk-triage! risk-map prs)
  nil)

(defn handle-control-action [{:keys [action workflow-id]}]
  (let [commands-dir (io/file (System/getProperty "user.home")
                              ".miniforge" "commands" (str workflow-id))
        cmd-file (io/file commands-dir (str (System/currentTimeMillis) ".edn"))]
    (.mkdirs commands-dir)
    (spit cmd-file (pr-str {:command action :timestamp (java.util.Date.)}))
    nil))

(defn handle-archive-workflows [{:keys [workflow-ids]}]
  (let [result (persistence/archive-workflows! workflow-ids)]
    (msg/workflows-archived result)))

(defn handle-chat-execute-action
  "Execute a chat-suggested action. Routes to the appropriate handler
   based on the action type keyword."
  [{:keys [action context]}]
  (try
    (let [action-type (:action action)
          result (case action-type
                   :review
                   (if-let [pr (:pr context)]
                     (handle-review-prs {:prs [pr]})
                     (msg/chat-action-result {:success? false :message "No PR in context for review"}))

                   :evaluate
                   (if-let [pr (:pr context)]
                     (handle-evaluate-policy {:pr pr :pr-id [(:pr/repo pr) (:pr/number pr)]})
                     (msg/chat-action-result {:success? false :message "No PR in context for evaluation"}))

                   :sync
                   (handle-sync-prs {})

                   :open
                   (if-let [url (get-in context [:pr :pr/url])]
                     (do (handle-open-url {:url url})
                         (msg/chat-action-result {:success? true :message (str "Opened " url)}))
                     (msg/chat-action-result {:success? false :message "No PR URL available"}))

                   :remediate
                   (if-let [pr (:pr context)]
                     (handle-remediate-prs {:prs [pr]})
                     (msg/chat-action-result {:success? false :message "No PR in context"}))

                   :decompose
                   (if-let [pr (:pr context)]
                     (handle-decompose-pr {:pr pr})
                     (msg/chat-action-result {:success? false :message "No PR in context"}))

                   (msg/chat-action-result
                    {:success? false
                     :message (str "Unknown action: " (name (or action-type :none)))}))]
        (if (= :msg/side-effect-error (first result))
          (let [payload (second result)
                message (or (get-in payload [:error :message])
                            (:message payload)
                            (:error payload)
                            "Action failed")]
            (msg/chat-action-result {:success? false :message message}))
          result))
    (catch Exception e
      (msg/chat-action-result {:success? false :message (.getMessage e)}))))

;; Lazy LLM client — initialized on first chat message
(def llm-client (delay (llm/create-client)))

(defn format-check-context [checks]
  (str/join ", " (map #(str (:name %) "=" (-> % (get :conclusion :unknown) name)) checks)))

(defn format-pr-summary-line
  "Format a PR as a one-line summary for prompt context."
  [pr]
  (str "- " (:pr/repo pr) "#" (:pr/number pr) " " (:pr/title pr)))

(defn build-pr-context-str
  "Build a text summary of PR data for the LLM system prompt."
  [{:keys [pr/behind-main? pr/branch pr/ci-status pr/number
           pr/policy pr/readiness pr/repo pr/risk pr/status pr/title
           pr/additions pr/deletions pr/changed-files-count pr/author]
    :or   {ci-status :unknown status :unknown}
    :as   pr}]
  (when pr
    (let [checks                                     (get pr :pr/ci-checks [])
          {:keys [readiness/score readiness/ready?]}  readiness
          {:keys [evaluation/passed?
                  evaluation/packs-applied]}          policy
          risk-level                                  (get risk :risk/level :unknown)
          risk-score                                  (:risk/score risk)
          risk-factors                                (:risk/factors risk)
          ci-str                                      (if (seq checks)
                                                        (str "CI checks: " (format-check-context checks))
                                                        (str "CI status: " (name ci-status)))
          total-lines                                 (+ (or additions 0) (or deletions 0))
          provider                                    (if (and repo (str/starts-with? (str repo) "gitlab:"))
                                                        "GitLab" "GitHub")]
      (str "PR: " repo "#" number " — " title "\n"
           "Provider: " provider "\n"
           "Branch: " branch "\n"
           "Author: " (or author "unknown") "\n"
           "Status: " (name status) "\n"
           ci-str "\n"
           "Behind main: " (if behind-main? "yes" "no") "\n"
           (when (pos? total-lines)
             (str "Change size: +" additions "/-" deletions " (" total-lines " total lines)"
                  (when (pos? (or changed-files-count 0))
                    (str ", " changed-files-count " files"))
                  "\n"))
           (when readiness
             (str "Readiness score: " score
                  (when ready? " (ready)")
                  "\n"))
           (when risk
             (str "Risk level: " (name risk-level)
                  (when risk-score
                    (str " (score: " (format "%.2f" (double risk-score)) ")"))
                  "\n"))
           (when (seq risk-factors)
             (str "Risk factors:\n"
                  (str/join "\n"
                    (map #(str "  - " (:explanation %)) risk-factors))
                  "\n"))
           (when policy
             (str "Policy: " (if passed? "passed" "FAILED")
                  (when-let [packs packs-applied]
                    (str " (packs: " (str/join ", " packs) ")"))
                  "\n"))))))

(defn- build-context-section
  "Build the context section for the chat system prompt."
  [context]
  (case (get context :type :unknown)
    :pr-detail
    (prompts/render :chat/context-pr-detail
                    {:pr-context (build-pr-context-str (:pr context))})

    :pr-fleet
    (let [prs (:selected-prs context)
          n (count prs)]
      (prompts/render :chat/context-pr-fleet
                      {:total-prs     (get context :total-prs 0)
                       :active-filter (name (get context :active-filter :open))
                       :selected-summary
                       (if (pos? n)
                         (str n " PR(s) selected:\n"
                              (str/join "\n"
                                (map format-pr-summary-line prs)))
                         "No PRs currently selected.")}))

    "Unknown context."))

(defn build-chat-system-prompt
  "Build a context-aware system prompt for the chat LLM."
  [context]
  (prompts/render :chat/system
                  {:max-line-width (get context :max-line-width 60)
                   :context        (build-context-section context)}))

(def action-pattern
  "Regex to parse [ACTION: type | label | description] from LLM response."
  #"\[ACTION:\s*(\S+)\s*\|\s*([^|]+?)\s*\|\s*([^\]]+?)\s*\]")

(defn action-match->action
  "Convert a regex match from action-pattern into a ChatAction map."
  [[_ action-type label description]]
  {:action      (keyword action-type)
   :label       (str/trim label)
   :description (str/trim description)})

(defn parse-actions
  "Extract structured actions from LLM response text.
   Returns [clean-content actions-vec]."
  [content]
  (let [matches (re-seq action-pattern content)
        actions (mapv action-match->action matches)
        clean (-> content
                  (str/replace action-pattern "")
                  str/trim)]
    [clean actions]))

(defn chat-msg->llm-msg
  "Convert an internal chat message to the LLM API format."
  [m]
  {:role (name (:role m)) :content (:content m)})

(defn handle-chat-send [{:keys [message context history]}]
  (try
    (let [system-prompt (build-chat-system-prompt context)
          messages (mapv chat-msg->llm-msg (or history []))
          request (cond-> {:system system-prompt}
                    (seq messages) (assoc :messages
                                         (conj messages
                                               {:role "user" :content message}))
                    (empty? messages) (assoc :prompt message))
          result (llm/complete @llm-client request)]
      (if (llm/success? result)
        (let [raw-content (llm/get-content result)
              [clean-content actions] (parse-actions raw-content)]
          (msg/chat-response clean-content actions))
        (msg/chat-response
         (str "LLM error: " (get-in (llm/get-error result) [:message] "Unknown error"))
         [])))
    (catch Exception e
      (msg/chat-response (str "Chat error: " (.getMessage e)) []))))

;------------------------------------------------------------------------------ Fleet risk triage

(def ^:private risk-line-pattern
  "Regex for parsing a single RISK: line from fleet triage LLM response."
  #"RISK:\s*(\S+#\d+)\s*\|\s*(\w+)\s*\|\s*(.*)")

(defn parse-risk-line
  "Parse a single RISK: line into {:id [repo num] :level str :reason str}, or nil."
  [line]
  (when-let [[_ id-str level reason] (re-matches risk-line-pattern line)]
    (let [[repo num-str] (str/split id-str #"#" 2)
          num (try (Integer/parseInt num-str) (catch Exception _ nil))]
      (when num
        {:id     [repo num]
         :level  (str/lower-case (str/trim level))
         :reason (str/trim reason)}))))

(defn parse-risk-triage-response
  "Parse the full LLM triage response into a vector of assessments."
  [content]
  (into [] (keep parse-risk-line) (str/split-lines content)))

(defn fleet-triage-system-prompt
  "Load the fleet triage system prompt from templates."
  []
  (prompts/get-template :fleet-triage/system))

(defn handle-fleet-risk-triage
  "Send all PR summaries to LLM for fleet-level risk triage.
   Returns per-PR risk assessments."
  [{:keys [pr-summaries]}]
  (try
    (let [summaries-text (str/join "\n" (map :summary pr-summaries))
          ids (mapv :id pr-summaries)
          result (llm/complete @llm-client {:system (fleet-triage-system-prompt)
                                                :prompt summaries-text})]
      (if (llm/success? result)
        (let [content (llm/get-content result)
              assessments (parse-risk-triage-response content)
              matched (if (seq assessments)
                        assessments
                        (mapv #(hash-map :id % :level "medium" :reason "Unable to assess") ids))]
          (msg/fleet-risk-triaged matched))
        (msg/fleet-risk-triaged-error "LLM request failed")))
    (catch Exception e
      (msg/fleet-risk-triaged-error (.getMessage e)))))

(defn handle-reload-workflow-detail
  "Reload workflow detail from the persisted event file on disk."
  [{:keys [workflow-id]}]
  (when-let [detail (persistence/load-workflow-detail workflow-id)]
    (msg/workflow-detail-loaded workflow-id detail)))

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
    :batch-evaluate-policy (handle-batch-evaluate-policy effect)
    :create-train      (handle-create-train train-mgr effect)
    :add-to-train      (handle-add-to-train train-mgr effect)
    :merge-next        (handle-merge-next train-mgr effect)
    :review-prs        (handle-review-prs effect)
    :remediate-prs     (handle-remediate-prs effect)
    :cache-policy-result (handle-cache-policy-result effect)
    :cache-risk-triage   (handle-cache-risk-triage effect)
    :decompose-pr      (handle-decompose-pr effect)
    :fetch-pr-diff     (handle-fetch-pr-diff effect)
    :control-action    (handle-control-action effect)
    :archive-workflows (handle-archive-workflows effect)
    :chat-send         (handle-chat-send effect)
    :chat-execute-action (handle-chat-execute-action effect)
    :fleet-risk-triage (handle-fleet-risk-triage effect)
    :reload-workflow-detail (handle-reload-workflow-detail effect)
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
                        (persistence-pr/load-all-into-model
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
    ;; Install SIGINT handler so Ctrl+C triggers graceful quit
    (let [quit-fn #(dosync (alter (:model-ref @app) assoc :quit? true))]
      (try
        (.addShutdownHook (Runtime/getRuntime)
                          (Thread. ^Runnable quit-fn))
        (catch Exception _)))
    ;; Block until quit
    (try
      (while (not (:quit? (tui/get-model app)))
        (Thread/sleep 50))
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
                        (persistence-pr/load-all-into-model
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
    ;; Install SIGINT handler so Ctrl+C triggers graceful quit
    (let [quit-fn #(dosync (alter (:model-ref @app) assoc :quit? true))]
      (try
        (.addShutdownHook (Runtime/getRuntime)
                          (Thread. ^Runnable quit-fn))
        (catch Exception _)))
    (try
      (while (not (:quit? (tui/get-model app)))
        (Thread/sleep 50))
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
