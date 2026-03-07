(ns ai.miniforge.tui-views.events-test
  "Tests for TUI event handlers in update/events namespace.
   Covers workflow, agent, PR, train, chat, and batch action handlers."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.tui-views.model :as model]
   [ai.miniforge.tui-views.update.events :as events]))

;; ---------------------------------------------------------------------------- Helpers

(defn fresh [] (model/init-model))

(defn with-workflow
  "Add a single workflow to the model."
  [model wf-id & {:keys [name] :or {name "test"}}]
  (events/handle-workflow-added model {:workflow-id wf-id :name name :spec nil}))

;; ---------------------------------------------------------------------------- Workflow events

(deftest handle-workflow-added-test
  (testing "adds workflow to list"
    (let [wf-id (random-uuid)
          m (with-workflow (fresh) wf-id :name "my-wf")]
      (is (= 1 (count (:workflows m))))
      (is (= "my-wf" (:name (first (:workflows m)))))
      (is (= :running (:status (first (:workflows m)))))
      (is (some? (:last-updated m))))))

(deftest handle-workflow-added-uses-spec-name-test
  (testing "uses spec name when name is nil"
    (let [m (events/handle-workflow-added (fresh)
              {:workflow-id (random-uuid) :name nil :spec {:name "Spec Name"}})]
      (is (= "Spec Name" (:name (first (:workflows m))))))))

(deftest handle-phase-changed-test
  (testing "updates workflow phase"
    (let [wf-id (random-uuid)
          m (-> (fresh)
                (with-workflow wf-id)
                (events/handle-phase-changed {:workflow-id wf-id :phase :implement}))]
      (is (= :implement (get-in m [:workflows 0 :phase])))))

  (testing "creates workflow row if missing (late event)"
    (let [wf-id (random-uuid)
          m (events/handle-phase-changed (fresh) {:workflow-id wf-id :phase :plan})]
      (is (= 1 (count (:workflows m))))
      (is (= :plan (get-in m [:workflows 0 :phase]))))))

(deftest handle-phase-changed-updates-detail-test
  (testing "updates detail phases when workflow is active in detail"
    (let [wf-id (random-uuid)
          m (-> (fresh)
                (with-workflow wf-id)
                (assoc-in [:detail :workflow-id] wf-id)
                (events/handle-phase-changed {:workflow-id wf-id :phase :plan}))]
      (is (some #(= :plan (:phase %)) (get-in m [:detail :phases]))))))

(deftest handle-phase-done-test
  (testing "increments workflow progress"
    (let [wf-id (random-uuid)
          m (-> (fresh)
                (with-workflow wf-id)
                (events/handle-phase-done {:workflow-id wf-id}))]
      (is (pos? (get-in m [:workflows 0 :progress]))))))

(deftest handle-phase-done-caps-at-100-test
  (testing "progress caps at 100"
    (let [wf-id (random-uuid)
          m (-> (fresh)
                (with-workflow wf-id)
                (assoc-in [:workflows 0 :progress] 95)
                (events/handle-phase-done {:workflow-id wf-id}))]
      (is (<= (get-in m [:workflows 0 :progress]) 100)))))

(deftest handle-workflow-done-test
  (testing "sets status and progress to 100"
    (let [wf-id (random-uuid)
          m (-> (fresh)
                (with-workflow wf-id)
                (events/handle-workflow-done {:workflow-id wf-id :status :success}))]
      (is (= :success (get-in m [:workflows 0 :status])))
      (is (= 100 (get-in m [:workflows 0 :progress]))))))

(deftest handle-workflow-failed-test
  (testing "sets status to failed and records error"
    (let [wf-id (random-uuid)
          m (-> (fresh)
                (with-workflow wf-id)
                (events/handle-workflow-failed {:workflow-id wf-id :error "LLM timeout"}))]
      (is (= :failed (get-in m [:workflows 0 :status])))
      (is (= "LLM timeout" (get-in m [:workflows 0 :error]))))))

;; ---------------------------------------------------------------------------- Agent events

(deftest handle-agent-status-test
  (testing "updates agent status on workflow"
    (let [wf-id (random-uuid)
          m (-> (fresh)
                (with-workflow wf-id)
                (events/handle-agent-status {:workflow-id wf-id
                                             :agent :planner
                                             :status :thinking
                                             :message "Analyzing"}))
          agent-entry (get-in m [:workflows 0 :agents :planner])]
      (is (= :thinking (:status agent-entry)))
      (is (= "Analyzing" (:message agent-entry))))))

(deftest handle-agent-output-test
  (testing "appends delta to detail agent output"
    (let [wf-id (random-uuid)
          m (-> (fresh)
                (with-workflow wf-id)
                (assoc-in [:detail :workflow-id] wf-id)
                (events/handle-agent-output {:workflow-id wf-id :delta "Hello "})
                (events/handle-agent-output {:workflow-id wf-id :delta "World"}))]
      (is (= "Hello World" (get-in m [:detail :agent-output]))))))

(deftest handle-agent-output-ignores-non-active-detail-test
  (testing "does not modify detail if workflow-id doesn't match"
    (let [wf-id (random-uuid)
          other-id (random-uuid)
          m (-> (fresh)
                (with-workflow wf-id)
                (assoc-in [:detail :workflow-id] other-id)
                (events/handle-agent-output {:workflow-id wf-id :delta "text"}))]
      (is (= "" (get-in m [:detail :agent-output]))))))

(deftest handle-agent-started-test
  (testing "sets agent status to started"
    (let [wf-id (random-uuid)
          m (-> (fresh)
                (with-workflow wf-id)
                (events/handle-agent-started {:workflow-id wf-id :agent :planner}))]
      (is (= :started (get-in m [:workflows 0 :agents :planner :status]))))))

(deftest handle-agent-completed-test
  (testing "sets agent status to completed"
    (let [wf-id (random-uuid)
          m (-> (fresh)
                (with-workflow wf-id)
                (events/handle-agent-completed {:workflow-id wf-id :agent :implementer}))]
      (is (= :completed (get-in m [:workflows 0 :agents :implementer :status]))))))

(deftest handle-agent-failed-test
  (testing "sets agent status to failed and flashes"
    (let [wf-id (random-uuid)
          m (-> (fresh)
                (with-workflow wf-id)
                (events/handle-agent-failed {:workflow-id wf-id :agent :reviewer}))]
      (is (= :failed (get-in m [:workflows 0 :agents :reviewer :status])))
      (is (str/includes? (:flash-message m) "reviewer")))))

;; ---------------------------------------------------------------------------- Gate events

(deftest handle-gate-result-passing-test
  (testing "passing gate records result"
    (let [wf-id (random-uuid)
          m (-> (fresh)
                (with-workflow wf-id)
                (events/handle-gate-result {:workflow-id wf-id :gate :lint :passed? true}))]
      (is (= 1 (count (get-in m [:workflows 0 :gate-results]))))
      ;; No flash for passing gate
      (is (nil? (:flash-message m))))))

(deftest handle-gate-result-failing-test
  (testing "failing gate sets flash message"
    (let [wf-id (random-uuid)
          m (-> (fresh)
                (with-workflow wf-id)
                (events/handle-gate-result {:workflow-id wf-id :gate :security :passed? false}))]
      (is (str/includes? (:flash-message m) "FAILED"))
      (is (str/includes? (:flash-message m) "security")))))

(deftest handle-gate-started-test
  (testing "sets flash message for gate running"
    (let [wf-id (random-uuid)
          m (-> (fresh)
                (with-workflow wf-id)
                (events/handle-gate-started {:workflow-id wf-id :gate :lint}))]
      (is (str/includes? (:flash-message m) "Gate running")))))

(deftest handle-gate-started-nil-gate-test
  (testing "handles nil gate gracefully"
    (let [wf-id (random-uuid)
          m (-> (fresh)
                (with-workflow wf-id)
                (events/handle-gate-started {:workflow-id wf-id :gate nil}))]
      (is (str/includes? (:flash-message m) "unknown")))))

;; ---------------------------------------------------------------------------- Tool events

(deftest handle-tool-invoked-test
  (testing "tool invoked updates agent status"
    (let [wf-id (random-uuid)
          m (-> (fresh)
                (with-workflow wf-id)
                (events/handle-tool-invoked {:workflow-id wf-id :agent :planner :tool :tools/read-file}))]
      (is (= :tool-running (get-in m [:workflows 0 :agents :planner :status]))))))

(deftest handle-tool-completed-test
  (testing "tool completed updates agent status"
    (let [wf-id (random-uuid)
          m (-> (fresh)
                (with-workflow wf-id)
                (events/handle-tool-completed {:workflow-id wf-id :agent :planner :tool :tools/read-file}))]
      (is (= :tool-completed (get-in m [:workflows 0 :agents :planner :status]))))))

(deftest handle-tool-nil-agent-test
  (testing "nil agent defaults to :agent"
    (let [wf-id (random-uuid)
          m (-> (fresh)
                (with-workflow wf-id)
                (events/handle-tool-invoked {:workflow-id wf-id :agent nil :tool :tools/read}))]
      (is (some? (get-in m [:workflows 0 :agents :agent]))))))

;; ---------------------------------------------------------------------------- PR events

(deftest handle-prs-synced-test
  (testing "replaces pr-items and shows count"
    (let [prs [{:pr/repo "r1" :pr/number 1} {:pr/repo "r2" :pr/number 2}]
          m (events/handle-prs-synced (fresh) {:pr-items prs})]
      (is (= 2 (count (:pr-items m))))
      (is (str/includes? (:flash-message m) "2 PRs")))))

(deftest handle-prs-synced-empty-test
  (testing "empty sync clears items"
    (let [m (-> (fresh)
                (assoc :pr-items [{:pr/repo "r1" :pr/number 1}])
                (events/handle-prs-synced {:pr-items []}))]
      (is (empty? (:pr-items m))))))

(deftest handle-prs-synced-nil-test
  (testing "nil pr-items treated as empty"
    (let [m (events/handle-prs-synced (fresh) {:pr-items nil})]
      (is (empty? (:pr-items m))))))

(deftest handle-policy-evaluated-passed-test
  (testing "passed policy shows passed flash"
    (let [m (-> (fresh)
                (assoc :pr-items [{:pr/repo "r1" :pr/number 42 :pr/title "test"}])
                (events/handle-policy-evaluated
                  {:pr-id ["r1" 42]
                   :result {:evaluation/passed? true}}))]
      (is (str/includes? (:flash-message m) "passed")))))

(deftest handle-policy-evaluated-failed-test
  (testing "failed policy shows violation count"
    (let [m (-> (fresh)
                (assoc :pr-items [{:pr/repo "r1" :pr/number 42}])
                (events/handle-policy-evaluated
                  {:pr-id ["r1" 42]
                   :result {:evaluation/passed? false
                            :evaluation/violations [{:rule :r1} {:rule :r2}]}}))]
      (is (str/includes? (:flash-message m) "FAILED"))
      (is (str/includes? (:flash-message m) "2 violation")))))

(deftest handle-policy-evaluated-error-test
  (testing "nil passed? shows error flash"
    (let [m (-> (fresh)
                (assoc :pr-items [{:pr/repo "r1" :pr/number 42}])
                (events/handle-policy-evaluated
                  {:pr-id ["r1" 42]
                   :result {:evaluation/passed? nil
                            :evaluation/error "timeout"}}))]
      (is (str/includes? (:flash-message m) "error")))))

(deftest handle-policy-evaluated-updates-detail-test
  (testing "updates active detail PR policy"
    (let [m (-> (fresh)
                (assoc :pr-items [{:pr/repo "r1" :pr/number 42}])
                (assoc-in [:detail :selected-pr] {:pr/repo "r1" :pr/number 42})
                (events/handle-policy-evaluated
                  {:pr-id ["r1" 42]
                   :result {:evaluation/passed? true}}))]
      (is (true? (get-in m [:detail :selected-pr :pr/policy :evaluation/passed?]))))))

;; ---------------------------------------------------------------------------- Train events

(deftest handle-train-created-test
  (testing "sets active train id and flashes"
    (let [m (events/handle-train-created (fresh) {:train-id :t1 :train-name "Release Train"})]
      (is (= :t1 (:active-train-id m)))
      (is (str/includes? (:flash-message m) "Release Train")))))

(deftest handle-prs-added-to-train-test
  (testing "sets selected train and flashes count"
    (let [m (events/handle-prs-added-to-train (fresh) {:train {:train/id :t1} :added 5})]
      (is (= {:train/id :t1} (get-in m [:detail :selected-train])))
      (is (str/includes? (:flash-message m) "5 PR")))))

(deftest handle-merge-started-test
  (testing "flashes merging message"
    (let [m (events/handle-merge-started (fresh) {:pr-number 99})]
      (is (str/includes? (:flash-message m) "#99")))))

;; ---------------------------------------------------------------------------- Batch action events

(deftest handle-review-completed-test
  (testing "updates PRs with policy results"
    (let [prs [{:pr/repo "r1" :pr/number 1} {:pr/repo "r2" :pr/number 2}]
          results [{:pr-id ["r1" 1] :result {:evaluation/passed? true}}
                   {:pr-id ["r2" 2] :result {:evaluation/passed? false}}]
          m (-> (fresh)
                (assoc :pr-items prs)
                (events/handle-review-completed {:results results}))]
      (is (some? (get-in m [:pr-items 0 :pr/policy])))
      (is (str/includes? (:flash-message m) "Review complete")))))

(deftest handle-remediation-completed-test
  (let [m (events/handle-remediation-completed (fresh) {:fixed 3 :failed 1})]
    (is (str/includes? (:flash-message m) "3 fixed"))))

(deftest handle-decomposition-started-test
  (let [m (events/handle-decomposition-started (fresh)
            {:pr-id [:repo 42] :plan {:sub-prs [1 2 3]}})]
    (is (str/includes? (:flash-message m) "3 sub-PRs"))))

;; ---------------------------------------------------------------------------- Repos events

(deftest handle-repos-discovered-success-test
  (testing "success path updates fleet repos"
    (let [m (events/handle-repos-discovered (fresh)
              {:success? true :repos ["r1" "r2"] :discovered 2 :added 1 :owner "acme"})]
      (is (= ["r1" "r2"] (:fleet-repos m)))
      (is (str/includes? (:flash-message m) "Discovered")))))

(deftest handle-repos-discovered-failure-test
  (testing "failure path shows error"
    (let [m (events/handle-repos-discovered (fresh)
              {:success? false :error "auth failed"})]
      (is (str/includes? (:flash-message m) "failed")))))

(deftest handle-repos-browsed-success-test
  (testing "success populates browse-repos and clears loading"
    (let [m (-> (fresh)
                (assoc :browse-repos-loading? true)
                (events/handle-repos-browsed {:success? true
                                              :repos ["r1" "r2"]
                                              :provider :github}))]
      (is (= ["r1" "r2"] (:browse-repos m)))
      (is (false? (:browse-repos-loading? m)))
      (is (str/includes? (:flash-message m) "github")))))

(deftest handle-repos-browsed-failure-test
  (testing "failure clears loading and shows error"
    (let [m (-> (fresh)
                (assoc :browse-repos-loading? true)
                (events/handle-repos-browsed {:success? false
                                              :error "rate limited"
                                              :error-source :rest}))]
      (is (false? (:browse-repos-loading? m)))
      (is (str/includes? (:flash-message m) "rate limited")))))

(deftest handle-repos-browsed-no-error-detail-test
  (testing "nil error shows fallback message"
    (let [m (-> (fresh)
                (assoc :browse-repos-loading? true)
                (events/handle-repos-browsed {:success? false :error nil}))]
      (is (str/includes? (:flash-message m) "no error details")))))

(deftest handle-repos-browsed-repo-manager-source-test
  (testing "source :repo-manager resets repo manager state"
    (let [m (-> (fresh)
                (assoc :browse-repos-loading? true
                       :selected-idx 5
                       :selected-ids #{"old"})
                (events/handle-repos-browsed {:success? true
                                              :source :repo-manager
                                              :repos ["r1"]}))]
      (is (= :browse (:repo-manager-source m)))
      (is (= 0 (:selected-idx m)))
      (is (= #{} (:selected-ids m))))))

;; ---------------------------------------------------------------------------- Chat events

(deftest handle-chat-response-test
  (testing "appends assistant message and clears pending"
    (let [m (-> (fresh)
                (assoc-in [:chat :pending?] true)
                (events/handle-chat-response {:content "Hello!" :actions []}))
          msgs (get-in m [:chat :messages])]
      (is (= 1 (count msgs)))
      (is (= :assistant (:role (first msgs))))
      (is (= "Hello!" (:content (first msgs))))
      (is (false? (get-in m [:chat :pending?]))))))

(deftest handle-chat-response-nil-content-test
  (testing "nil content defaults to 'No response'"
    (let [m (events/handle-chat-response (fresh) {:content nil :actions nil})
          msg (first (get-in m [:chat :messages]))]
      (is (= "No response" (:content msg))))))

(deftest handle-chat-action-result-test
  (testing "success action result sets flash"
    (let [m (events/handle-chat-action-result (fresh) {:success? true :message "Done"})]
      (is (= "Done" (:flash-message m)))))

  (testing "failure with no message uses default"
    (let [m (events/handle-chat-action-result (fresh) {:success? false})]
      (is (= "Action failed" (:flash-message m))))))

;; ---------------------------------------------------------------------------- ensure-workflow helper

(deftest ensure-workflow-creates-row-for-unknown-id-test
  (testing "events for unknown workflow-ids auto-create a row"
    (let [wf-id (random-uuid)
          m (events/handle-agent-status (fresh)
              {:workflow-id wf-id :agent :planner :status :thinking :message "hi"})]
      (is (= 1 (count (:workflows m))))
      (is (= wf-id (get-in m [:workflows 0 :id]))))))

(deftest ensure-workflow-does-not-duplicate-test
  (testing "ensure-workflow doesn't add duplicates"
    (let [wf-id (random-uuid)
          m (-> (fresh)
                (with-workflow wf-id)
                (events/handle-agent-status {:workflow-id wf-id :agent :a :status :ok :message ""}))
          ;; Apply another event to same workflow
          m2 (events/handle-phase-changed m {:workflow-id wf-id :phase :plan})]
      (is (= 1 (count (:workflows m2)))))))

;; ---------------------------------------------------------------------------- Extracted helper functions

(deftest update-phase-status-test
  (testing "updates matching phase status and duration"
    (let [phases [{:phase :plan :status :running}
                  {:phase :implement :status :running}]
          result (events/update-phase-status phases :plan :success 1200)]
      (is (= :success (:status (first result))))
      (is (= 1200 (:duration-ms (first result))))
      (is (= :running (:status (second result))))))

  (testing "leaves phases unchanged when no match"
    (let [phases [{:phase :plan :status :running}]
          result (events/update-phase-status phases :review :success 500)]
      (is (= :running (:status (first result))))
      (is (nil? (:duration-ms (first result)))))))

(deftest normalize-artifact-test
  (testing "map artifact gets phase key added"
    (let [a (events/normalize-artifact {:id "a1" :type :file :name "foo.clj"} :implement)]
      (is (= :implement (:phase a)))
      (is (= "a1" (:id a)))))

  (testing "non-map artifact gets wrapped with defaults"
    (let [a (events/normalize-artifact "some-id" :plan)]
      (is (= "some-id" (:id a)))
      (is (= :plan (:phase a)))
      (is (= :unknown (:type a)))
      (is (= "some-id" (:name a))))))

(deftest apply-phase-completion-test
  (testing "updates phase status and appends artifacts"
    (let [model {:detail {:phases [{:phase :plan :status :running}]
                          :artifacts []}}
          result (events/apply-phase-completion model :plan :success 800
                   [{:id "a1" :type :file :name "f.clj"}])]
      (is (= :success (get-in result [:detail :phases 0 :status])))
      (is (= 800 (get-in result [:detail :phases 0 :duration-ms])))
      (is (= 1 (count (get-in result [:detail :artifacts]))))))

  (testing "no artifacts leaves artifacts unchanged"
    (let [model {:detail {:phases [{:phase :plan :status :running}]
                          :artifacts [{:id "existing"}]}}
          result (events/apply-phase-completion model :plan :success 500 nil)]
      (is (= 1 (count (get-in result [:detail :artifacts])))))))

(deftest apply-workflow-completion-test
  (testing "sets evidence bundle-id and duration"
    (let [result (events/apply-workflow-completion {} "bundle-123" 5000)]
      (is (= "bundle-123" (get-in result [:detail :evidence :bundle-id])))
      (is (= 5000 (get-in result [:detail :duration-ms])))))

  (testing "nil values are not assoc'd"
    (let [result (events/apply-workflow-completion {} nil nil)]
      (is (nil? (get-in result [:detail :evidence :bundle-id])))
      (is (nil? (get-in result [:detail :duration-ms]))))))

(deftest apply-evidence-intent-test
  (testing "sets evidence intent from spec"
    (let [result (events/apply-evidence-intent {} {:name "My Spec"} "fallback")]
      (is (= "My Spec" (get-in result [:detail :evidence :intent :description])))))

  (testing "uses name fallback when spec has no :name"
    (let [result (events/apply-evidence-intent {} {:tasks []} "fallback")]
      (is (= "fallback" (get-in result [:detail :evidence :intent :description])))))

  (testing "nil spec is a no-op"
    (let [result (events/apply-evidence-intent {:existing :data} nil "name")]
      (is (= {:existing :data} result)))))

;; ---------------------------------------------------------------------------- PR update/remove

(deftest handle-pr-updated-test
  (testing "merges updated fields into matching PR"
    (let [m (-> (fresh)
                (assoc :pr-items [{:pr/repo "r1" :pr/number 1 :pr/title "Old" :pr/status :open}])
                (events/handle-pr-updated {:pr/repo "r1" :pr/number 1 :pr/status :merge-ready}))]
      (is (= :merge-ready (get-in m [:pr-items 0 :pr/status])))
      (is (= "Old" (get-in m [:pr-items 0 :pr/title]))))))

(deftest handle-pr-removed-test
  (testing "removes matching PR from items"
    (let [m (-> (fresh)
                (assoc :pr-items [{:pr/repo "r1" :pr/number 1}
                                  {:pr/repo "r2" :pr/number 2}])
                (events/handle-pr-removed {:pr/repo "r1" :pr/number 1}))]
      (is (= 1 (count (:pr-items m))))
      (is (= "r2" (get-in m [:pr-items 0 :pr/repo]))))))
