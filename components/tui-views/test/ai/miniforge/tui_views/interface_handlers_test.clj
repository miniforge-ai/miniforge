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

(ns ai.miniforge.tui-views.interface-handlers-test
  "Tests for side-effect handlers in interface.clj that lack dedicated coverage.

   Covers:
   - handle-batch-evaluate-policy (batch policy evaluation)
   - handle-remediate-prs (remediation stub)
   - handle-decompose-pr (decomposition stub)
   - handle-cache-policy-result / handle-cache-risk-triage (caching side-effects)
   - handle-control-action (filesystem command writing)
   - handle-archive-workflows (workflow archival)
   - handle-fleet-risk-triage (LLM-based fleet risk triage)
   - handle-reload-workflow-detail (workflow detail reload)
   - dispatch-effect routing for all effect types
   - parse-risk-triage-response / parse-risk-line / action-match->action"
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.string :as str]
   [ai.miniforge.tui-views.interface :as iface]
   [ai.miniforge.tui-views.persistence :as persistence]
   [ai.miniforge.tui-views.persistence.pr :as persistence-pr]
   [ai.miniforge.tui-views.persistence.pr-cache :as pr-cache]
   [ai.miniforge.tui-views.persistence.github :as github]
   [ai.miniforge.policy-pack.interface :as policy-pack]
   [ai.miniforge.llm.interface :as llm]))

;; ---------------------------------------------------------------------------- Helpers

(defn msg-type [m] (first m))
(defn msg-payload [m] (second m))

(defn make-pr
  "Build a minimal PR map for testing."
  [repo number & {:keys [title policy violations]
                  :or {title "Test PR"}}]
  (cond-> {:pr/repo repo :pr/number number :pr/title title}
    policy     (assoc :pr/policy policy)
    violations (assoc-in [:pr/policy :evaluation/violations] violations)))

;; ---------------------------------------------------------------------------- handle-batch-evaluate-policy

(deftest handle-batch-evaluate-policy-success-test
  (testing "evaluates policy for multiple PRs and returns review-completed"
    (with-redefs [persistence-pr/load-policy-packs (fn [] [{:pack/name "security"}])
                  policy-pack/evaluate-external-pr
                  (fn [packs _pr]
                    (is (= 1 (count packs)))
                    {:evaluation/passed? true})]
      (let [prs [(make-pr "a/b" 1) (make-pr "c/d" 2)]
            m (iface/handle-batch-evaluate-policy {:prs prs})]
        (is (= :msg/review-completed (msg-type m)))
        (is (= 2 (count (:results (msg-payload m)))))
        (is (every? #(true? (get-in % [:result :evaluation/passed?]))
                    (:results (msg-payload m))))))))

(deftest handle-batch-evaluate-policy-pr-ids-test
  (testing "each result contains correct pr-id"
    (with-redefs [persistence-pr/load-policy-packs (fn [] [])
                  policy-pack/evaluate-external-pr (fn [_ _] {:evaluation/passed? true})]
      (let [prs [(make-pr "a/b" 1) (make-pr "c/d" 2)]
            results (:results (msg-payload (iface/handle-batch-evaluate-policy {:prs prs})))]
        (is (= ["a/b" 1] (:pr-id (first results))))
        (is (= ["c/d" 2] (:pr-id (second results))))))))

(deftest handle-batch-evaluate-policy-individual-exception-test
  (testing "individual PR evaluation exception is caught per-PR"
    (let [call-count (atom 0)]
      (with-redefs [persistence-pr/load-policy-packs (fn [] [])
                    policy-pack/evaluate-external-pr
                    (fn [_ pr]
                      (swap! call-count inc)
                      (if (= 1 (:pr/number pr))
                        (throw (Exception. "bad PR"))
                        {:evaluation/passed? true}))]
        (let [prs [(make-pr "a/b" 1) (make-pr "c/d" 2)]
              results (:results (msg-payload (iface/handle-batch-evaluate-policy {:prs prs})))]
          (is (= 2 (count results)))
          ;; First PR has error
          (is (nil? (get-in (first results) [:result :evaluation/passed?])))
          (is (= "bad PR" (get-in (first results) [:result :evaluation/error])))
          ;; Second PR succeeds
          (is (true? (get-in (second results) [:result :evaluation/passed?]))))))))

(deftest handle-batch-evaluate-policy-load-packs-exception-test
  (testing "exception loading packs returns empty results"
    (with-redefs [persistence-pr/load-policy-packs (fn [] (throw (Exception. "no packs")))]
      (let [m (iface/handle-batch-evaluate-policy {:prs [(make-pr "r" 1)]})]
        (is (= :msg/review-completed (msg-type m)))
        (is (= [] (:results (msg-payload m))))))))

(deftest handle-batch-evaluate-policy-empty-prs-test
  (testing "empty PR list returns empty results"
    (with-redefs [persistence-pr/load-policy-packs (fn [] [])]
      (let [m (iface/handle-batch-evaluate-policy {:prs []})]
        (is (= :msg/review-completed (msg-type m)))
        (is (= [] (:results (msg-payload m))))))))

;; ---------------------------------------------------------------------------- handle-remediate-prs

(deftest handle-remediate-prs-stub-test
  (testing "returns remediation-completed with zero fixed"
    (let [m (iface/handle-remediate-prs {:prs [(make-pr "r" 1)]})]
      (is (= :msg/remediation-completed (msg-type m)))
      (is (= 0 (:fixed (msg-payload m))))
      (is (string? (:message (msg-payload m)))))))

(deftest handle-remediate-prs-counts-fixable-test
  (testing "counts PRs with violations as fixable"
    (let [prs [(make-pr "r" 1 :violations [{:rule "no-large-pr"}])
               (make-pr "r" 2 :violations [{:rule "require-tests"}])
               (make-pr "r" 3)]  ;; no violations
          m (iface/handle-remediate-prs {:prs prs})]
      (is (= 2 (:failed (msg-payload m)))))))

(deftest handle-remediate-prs-empty-list-test
  (testing "empty PR list returns zero counts"
    (let [m (iface/handle-remediate-prs {:prs []})]
      (is (= 0 (:fixed (msg-payload m))))
      (is (= 0 (:failed (msg-payload m)))))))

;; ---------------------------------------------------------------------------- handle-decompose-pr

(deftest handle-decompose-pr-returns-started-msg-test
  (testing "returns decomposition-started with pr-id"
    (let [pr (make-pr "acme/app" 42)
          m (iface/handle-decompose-pr {:pr pr})]
      (is (= :msg/decomposition-started (msg-type m)))
      (is (= ["acme/app" 42] (:pr-id (msg-payload m)))))))

(deftest handle-decompose-pr-plan-shape-test
  (testing "payload has expected structure with sub-prs and message"
    (let [m (iface/handle-decompose-pr {:pr (make-pr "r" 1)})
          payload (msg-payload m)]
      (is (map? payload))
      (is (contains? payload :sub-prs))
      (is (contains? payload :message)))))

;; ---------------------------------------------------------------------------- handle-cache-policy-result

(deftest handle-cache-policy-result-calls-persist-test
  (testing "calls pr-cache/persist-policy-result! and returns nil"
    (let [persisted (atom nil)]
      (with-redefs [pr-cache/persist-policy-result!
                    (fn [pr-id result prs] (reset! persisted {:pr-id pr-id :result result :prs prs}))]
        (let [result (iface/handle-cache-policy-result
                       {:pr-id ["r" 1] :result {:passed? true} :prs [{:pr/repo "r"}]})]
          (is (nil? result))
          (is (= ["r" 1] (:pr-id @persisted)))
          (is (= {:passed? true} (:result @persisted))))))))

;; ---------------------------------------------------------------------------- handle-cache-risk-triage

(deftest handle-cache-risk-triage-calls-persist-test
  (testing "calls pr-cache/persist-risk-triage! and returns nil"
    (let [persisted (atom nil)]
      (with-redefs [pr-cache/persist-risk-triage!
                    (fn [risk-map prs] (reset! persisted {:risk-map risk-map :prs prs}))]
        (let [result (iface/handle-cache-risk-triage
                       {:risk-map {["r" 1] {:level :high}} :prs [{:pr/repo "r"}]})]
          (is (nil? result))
          (is (= {["r" 1] {:level :high}} (:risk-map @persisted))))))))

;; ---------------------------------------------------------------------------- handle-control-action

(deftest handle-control-action-returns-nil-test
  (testing "returns nil (fire-and-forget side-effect)"
    (let [wf-id (random-uuid)
          ;; We can't easily test file writing without temp dirs, but we can
          ;; verify the function doesn't throw and returns nil.
          ;; Using a temp dir via system property override.
          tmp-dir (System/getProperty "java.io.tmpdir")
          original-home (System/getProperty "user.home")]
      (try
        (System/setProperty "user.home" tmp-dir)
        (is (nil? (iface/handle-control-action {:action :pause :workflow-id wf-id})))
        (finally
          (System/setProperty "user.home" original-home))))))

;; ---------------------------------------------------------------------------- handle-archive-workflows

(deftest handle-archive-workflows-success-test
  (testing "returns workflows-archived message"
    (with-redefs [persistence/archive-workflows!
                  (fn [ids] {:archived (count ids) :failed 0})]
      (let [m (iface/handle-archive-workflows {:workflow-ids [:w1 :w2 :w3]})]
        (is (= :msg/workflows-archived (msg-type m)))
        (is (= 3 (:archived (msg-payload m))))
        (is (= 0 (:failed (msg-payload m))))))))

(deftest handle-archive-workflows-empty-ids-test
  (testing "empty workflow list returns zero archived"
    (with-redefs [persistence/archive-workflows!
                  (fn [_ids] {:archived 0 :failed 0})]
      (let [m (iface/handle-archive-workflows {:workflow-ids []})]
        (is (= 0 (:archived (msg-payload m))))))))

;; ---------------------------------------------------------------------------- handle-fleet-risk-triage

(defn mock-llm-success [_content]
  (reify
    Object
    (toString [_] "mock-result")))

(deftest handle-fleet-risk-triage-success-test
  (testing "parses LLM response into risk assessments"
    (let [llm-response "RISK: acme/app#42 | high | Large change\nRISK: other/lib#7 | low | Trivial fix"]
      (with-redefs [llm/complete   (fn [_ _] ::result)
                    llm/success?   (fn [r] (= ::result r))
                    llm/get-content (fn [_] llm-response)
                    iface/fleet-triage-system-prompt (fn [] "system prompt")]
        (let [m (iface/handle-fleet-risk-triage
                  {:pr-summaries [{:id ["acme/app" 42] :summary "s1"}
                                  {:id ["other/lib" 7] :summary "s2"}]})]
          (is (= :msg/fleet-risk-triaged (msg-type m)))
          (let [assessments (:assessments (msg-payload m))]
            (is (= 2 (count assessments)))
            (is (= ["acme/app" 42] (:id (first assessments))))
            (is (= "high" (:level (first assessments))))
            (is (= ["other/lib" 7] (:id (second assessments))))
            (is (= "low" (:level (second assessments))))))))))

(deftest handle-fleet-risk-triage-llm-failure-test
  (testing "LLM failure returns error message"
    (with-redefs [llm/complete   (fn [_ _] ::fail)
                  llm/success?   (fn [_] false)
                  iface/fleet-triage-system-prompt (fn [] "prompt")]
      (let [m (iface/handle-fleet-risk-triage {:pr-summaries [{:id ["r" 1] :summary "s"}]})]
        (is (= :msg/fleet-risk-triaged (msg-type m)))
        (is (= "LLM request failed" (:error (msg-payload m))))))))

(deftest handle-fleet-risk-triage-exception-test
  (testing "exception returns error message"
    (with-redefs [llm/complete (fn [_ _] (throw (Exception. "network error")))
                  iface/fleet-triage-system-prompt (fn [] "prompt")]
      (let [m (iface/handle-fleet-risk-triage {:pr-summaries [{:id ["r" 1] :summary "s"}]})]
        (is (= :msg/fleet-risk-triaged (msg-type m)))
        (is (str/includes? (:error (msg-payload m)) "network error"))))))

(deftest handle-fleet-risk-triage-no-parseable-lines-test
  (testing "unparseable LLM response falls back to medium for all PRs"
    (with-redefs [llm/complete    (fn [_ _] ::result)
                  llm/success?    (fn [_] true)
                  llm/get-content (fn [_] "I can't assess risk for these PRs.")
                  iface/fleet-triage-system-prompt (fn [] "prompt")]
      (let [_ids [["a/b" 1] ["c/d" 2]]
            m (iface/handle-fleet-risk-triage
                {:pr-summaries [{:id ["a/b" 1] :summary "s1"}
                                {:id ["c/d" 2] :summary "s2"}]})]
        (is (= :msg/fleet-risk-triaged (msg-type m)))
        (let [assessments (:assessments (msg-payload m))]
          (is (= 2 (count assessments)))
          (is (every? #(= "medium" (:level %)) assessments)))))))

;; ---------------------------------------------------------------------------- handle-reload-workflow-detail

(deftest handle-reload-workflow-detail-found-test
  (testing "returns workflow-detail-loaded when detail exists"
    (let [wf-id (random-uuid)
          detail {:phases [:plan :implement]}]
      (with-redefs [persistence/load-workflow-detail (fn [id] (when (= id wf-id) detail))]
        (let [m (iface/handle-reload-workflow-detail {:workflow-id wf-id})]
          (is (= :msg/workflow-detail-loaded (msg-type m)))
          (is (= wf-id (:workflow-id (msg-payload m))))
          (is (= detail (:detail (msg-payload m)))))))))

(deftest handle-reload-workflow-detail-not-found-test
  (testing "returns nil when no detail on disk"
    (with-redefs [persistence/load-workflow-detail (fn [_] nil)]
      (is (nil? (iface/handle-reload-workflow-detail {:workflow-id (random-uuid)}))))))

;; ---------------------------------------------------------------------------- parse-risk-triage-response

(deftest parse-risk-triage-response-multiple-lines-test
  (testing "parses multiple RISK: lines"
    (let [content "RISK: org/repo#10 | high | Security issue\nRISK: org/repo#20 | low | Docs only\nSome other text"
          result (iface/parse-risk-triage-response content)]
      (is (= 2 (count result)))
      (is (= ["org/repo" 10] (:id (first result))))
      (is (= "high" (:level (first result))))
      (is (= "Security issue" (:reason (first result))))
      (is (= ["org/repo" 20] (:id (second result)))))))

(deftest parse-risk-triage-response-empty-test
  (testing "returns empty vector for no RISK: lines"
    (is (= [] (iface/parse-risk-triage-response "no risk lines here")))))

(deftest parse-risk-triage-response-mixed-content-test
  (testing "skips non-RISK lines"
    (let [content "Header\nRISK: r#1 | medium | reason\nFooter\nRISK: r#2 | high | big"]
      (is (= 2 (count (iface/parse-risk-triage-response content)))))))

;; ---------------------------------------------------------------------------- parse-risk-line

(deftest parse-risk-line-valid-test
  (testing "parses valid RISK: line"
    (let [r (iface/parse-risk-line "RISK: owner/repo#42 | HIGH | Large change touching core")]
      (is (= ["owner/repo" 42] (:id r)))
      (is (= "high" (:level r)))
      (is (= "Large change touching core" (:reason r))))))

(deftest parse-risk-line-invalid-format-test
  (testing "returns nil for non-matching lines"
    (is (nil? (iface/parse-risk-line "not a risk line")))
    (is (nil? (iface/parse-risk-line "RISK: missing pipes")))))

(deftest parse-risk-line-non-numeric-number-test
  (testing "returns nil when PR number is not numeric"
    (is (nil? (iface/parse-risk-line "RISK: owner/repo#abc | high | reason")))))

(deftest parse-risk-line-trims-whitespace-test
  (testing "trims level and reason whitespace"
    (let [r (iface/parse-risk-line "RISK: r#1 |  medium  |  some reason  ")]
      (is (= "medium" (:level r)))
      (is (= "some reason" (:reason r))))))

;; ---------------------------------------------------------------------------- action-match->action

(deftest action-match->action-test
  (testing "converts regex match to ChatAction map"
    (let [match ["[ACTION: review | Review PR | Run policy]" "review" "Review PR" "Run policy"]
          result (iface/action-match->action match)]
      (is (= :review (:action result)))
      (is (= "Review PR" (:label result)))
      (is (= "Run policy" (:description result))))))

(deftest action-match->action-trims-test
  (testing "trims label and description"
    (let [match ["_" "sync" "  Sync  " "  Refresh PRs  "]
          result (iface/action-match->action match)]
      (is (= "Sync" (:label result)))
      (is (= "Refresh PRs" (:description result))))))

;; ---------------------------------------------------------------------------- parse-actions

(deftest parse-actions-no-actions-test
  (testing "returns clean content and empty actions when no ACTION markers"
    (let [[clean actions] (iface/parse-actions "Just normal text.")]
      (is (= "Just normal text." clean))
      (is (= [] actions)))))

(deftest parse-actions-single-action-test
  (testing "extracts single action and cleans content"
    (let [text "Here is my analysis.\n[ACTION: review | Review | Run review]\nDone."
          [clean actions] (iface/parse-actions text)]
      (is (= 1 (count actions)))
      (is (= :review (:action (first actions))))
      (is (not (str/includes? clean "[ACTION:")))
      (is (str/includes? clean "Here is my analysis."))
      (is (str/includes? clean "Done.")))))

(deftest parse-actions-empty-string-test
  (testing "empty string returns empty clean and no actions"
    (let [[clean actions] (iface/parse-actions "")]
      (is (= "" clean))
      (is (= [] actions)))))

;; ---------------------------------------------------------------------------- chat-msg->llm-msg

(deftest chat-msg->llm-msg-user-test
  (testing "converts user role"
    (let [result (iface/chat-msg->llm-msg {:role :user :content "hello"})]
      (is (= "user" (:role result)))
      (is (= "hello" (:content result))))))

(deftest chat-msg->llm-msg-system-test
  (testing "converts system role"
    (let [result (iface/chat-msg->llm-msg {:role :system :content "sys"})]
      (is (= "system" (:role result))))))

;; ---------------------------------------------------------------------------- dispatch-effect comprehensive routing

(deftest dispatch-effect-routes-sync-prs-test
  (testing ":sync-prs routes correctly"
    (with-redefs [persistence-pr/load-pr-items (fn [_] {:prs [] :error nil})
                  pr-cache/read-cache          (fn [] {})
                  pr-cache/apply-cached-policy  (fn [prs _] prs)
                  pr-cache/apply-cached-agent-risk (fn [_ _] {})]
      (let [m (iface/dispatch-effect nil {:type :sync-prs})]
        (is (= :msg/prs-synced (msg-type m)))))))

(deftest dispatch-effect-routes-discover-repos-test
  (testing ":discover-repos routes correctly"
    (with-redefs [persistence-pr/discover-repos (fn [_owner] {:repos []})]
      (let [m (iface/dispatch-effect nil {:type :discover-repos :owner "acme"})]
        (is (= :msg/repos-discovered (msg-type m)))))))

(deftest dispatch-effect-routes-browse-repos-test
  (testing ":browse-repos routes correctly"
    (with-redefs [persistence-pr/browse-repos (fn [_opts] {:repos []})]
      (let [m (iface/dispatch-effect nil {:type :browse-repos :owner "acme"
                                           :provider :github :limit 10 :source :browse})]
        (is (= :msg/repos-browsed (msg-type m)))))))

(deftest dispatch-effect-routes-open-url-nil-test
  (testing ":open-url with nil returns nil"
    (is (nil? (iface/dispatch-effect nil {:type :open-url :url nil})))))

(deftest dispatch-effect-routes-evaluate-policy-test
  (testing ":evaluate-policy routes correctly"
    (with-redefs [persistence-pr/load-policy-packs (fn [] [])
                  policy-pack/evaluate-external-pr (fn [_ _] {:evaluation/passed? true})]
      (let [m (iface/dispatch-effect nil {:type :evaluate-policy
                                           :pr (make-pr "r" 1)
                                           :pr-id ["r" 1]})]
        (is (= :msg/policy-evaluated (msg-type m)))))))

(deftest dispatch-effect-routes-batch-evaluate-policy-test
  (testing ":batch-evaluate-policy routes correctly"
    (with-redefs [persistence-pr/load-policy-packs (fn [] [])
                  policy-pack/evaluate-external-pr (fn [_ _] {:evaluation/passed? true})]
      (let [m (iface/dispatch-effect nil {:type :batch-evaluate-policy
                                           :prs [(make-pr "r" 1)]})]
        (is (= :msg/review-completed (msg-type m)))))))

(deftest dispatch-effect-routes-review-prs-test
  (testing ":review-prs routes correctly"
    (with-redefs [persistence-pr/load-policy-packs (fn [] [])
                  policy-pack/evaluate-external-pr (fn [_ _] {:evaluation/passed? true})]
      (let [m (iface/dispatch-effect nil {:type :review-prs :prs [(make-pr "r" 1)]})]
        (is (= :msg/review-completed (msg-type m)))))))

(deftest dispatch-effect-routes-remediate-prs-test
  (testing ":remediate-prs routes correctly"
    (let [m (iface/dispatch-effect nil {:type :remediate-prs :prs [(make-pr "r" 1)]})]
      (is (= :msg/remediation-completed (msg-type m))))))

(deftest dispatch-effect-routes-decompose-pr-test
  (testing ":decompose-pr routes correctly"
    (let [m (iface/dispatch-effect nil {:type :decompose-pr :pr (make-pr "r" 1)})]
      (is (= :msg/decomposition-started (msg-type m))))))

(deftest dispatch-effect-routes-cache-policy-result-test
  (testing ":cache-policy-result routes correctly and returns nil"
    (with-redefs [pr-cache/persist-policy-result! (fn [_ _ _] nil)]
      (is (nil? (iface/dispatch-effect nil {:type :cache-policy-result
                                             :pr-id ["r" 1]
                                             :result {:passed? true}
                                             :prs []}))))))

(deftest dispatch-effect-routes-cache-risk-triage-test
  (testing ":cache-risk-triage routes correctly and returns nil"
    (with-redefs [pr-cache/persist-risk-triage! (fn [_ _] nil)]
      (is (nil? (iface/dispatch-effect nil {:type :cache-risk-triage
                                             :risk-map {}
                                             :prs []}))))))

(deftest dispatch-effect-routes-archive-workflows-test
  (testing ":archive-workflows routes correctly"
    (with-redefs [persistence/archive-workflows! (fn [ids] {:archived (count ids)})]
      (let [m (iface/dispatch-effect nil {:type :archive-workflows :workflow-ids [:w1]})]
        (is (= :msg/workflows-archived (msg-type m)))))))

(deftest dispatch-effect-routes-reload-workflow-detail-test
  (testing ":reload-workflow-detail routes correctly"
    (let [wf-id (random-uuid)]
      (with-redefs [persistence/load-workflow-detail (fn [_id] {:phases [:plan]})]
        (let [m (iface/dispatch-effect nil {:type :reload-workflow-detail :workflow-id wf-id})]
          (is (= :msg/workflow-detail-loaded (msg-type m))))))))

(deftest dispatch-effect-routes-fetch-pr-diff-test
  (testing ":fetch-pr-diff routes correctly"
    (with-redefs [github/fetch-pr-diff-and-detail
                  (fn [r n] {:diff "d" :detail {:title "T"} :repo r :number n})]
      (let [m (iface/dispatch-effect nil {:type :fetch-pr-diff :repo "r" :number 1})]
        (is (= :msg/pr-diff-fetched (msg-type m)))))))

(deftest dispatch-effect-unknown-returns-nil-test
  (testing "unknown effect type returns nil"
    (is (nil? (iface/dispatch-effect nil {:type :nonexistent-effect})))))

;; ---------------------------------------------------------------------------- handle-review-prs

(deftest handle-review-prs-success-test
  (testing "evaluates policy for each PR"
    (with-redefs [persistence-pr/load-policy-packs (fn [] [{:pack/name "p1"}])
                  policy-pack/evaluate-external-pr
                  (fn [_ pr] {:evaluation/passed? (= 1 (:pr/number pr))})]
      (let [m (iface/handle-review-prs {:prs [(make-pr "r" 1) (make-pr "r" 2)]})]
        (is (= :msg/review-completed (msg-type m)))
        (let [results (:results (msg-payload m))]
          (is (= 2 (count results)))
          (is (true? (get-in (first results) [:result :evaluation/passed?])))
          (is (false? (get-in (second results) [:result :evaluation/passed?]))))))))

(deftest handle-review-prs-exception-test
  (testing "exception returns side-effect-error"
    (with-redefs [persistence-pr/load-policy-packs (fn [] (throw (Exception. "boom")))]
      (let [m (iface/handle-review-prs {:prs [(make-pr "r" 1)]})]
        (is (= :msg/side-effect-error (msg-type m)))))))

;; ---------------------------------------------------------------------------- handle-evaluate-policy

(deftest handle-evaluate-policy-success-test
  (testing "returns policy-evaluated on success"
    (with-redefs [persistence-pr/load-policy-packs (fn [] [])
                  policy-pack/evaluate-external-pr
                  (fn [_ _] {:evaluation/passed? true})]
      (let [m (iface/handle-evaluate-policy {:pr (make-pr "r" 1) :pr-id ["r" 1]})]
        (is (= :msg/policy-evaluated (msg-type m)))
        (is (= ["r" 1] (:pr-id (msg-payload m))))
        (is (true? (get-in (msg-payload m) [:result :evaluation/passed?])))))))

(deftest handle-evaluate-policy-exception-test
  (testing "exception returns policy-evaluated with error"
    (with-redefs [persistence-pr/load-policy-packs (fn [] (throw (Exception. "no packs")))]
      (let [m (iface/handle-evaluate-policy {:pr (make-pr "r" 1) :pr-id ["r" 1]})]
        (is (= :msg/policy-evaluated (msg-type m)))
        (is (nil? (get-in (msg-payload m) [:result :evaluation/passed?])))
        (is (= "no packs" (get-in (msg-payload m) [:result :evaluation/error])))))))

;; ---------------------------------------------------------------------------- format-pr-summary-line

(deftest format-pr-summary-line-test
  (testing "formats as dash-prefixed line"
    (let [pr {:pr/repo "acme/app" :pr/number 42 :pr/title "Fix bug"}
          result (iface/format-pr-summary-line pr)]
      (is (= "- acme/app#42 Fix bug" result)))))

(deftest format-pr-summary-line-empty-title-test
  (testing "handles empty title"
    (let [result (iface/format-pr-summary-line {:pr/repo "r" :pr/number 1 :pr/title ""})]
      (is (= "- r#1 " result)))))
