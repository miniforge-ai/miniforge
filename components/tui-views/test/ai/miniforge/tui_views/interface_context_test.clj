(ns ai.miniforge.tui-views.interface-context-test
  "Tests for pure helper functions in interface.clj:
   - build-pr-context-str (comprehensive PR context rendering)
   - format-check-context
   - build-context-section
   - build-chat-system-prompt
   - handle-chat-execute-action routing
   - dispatch-effect routing"
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.string :as str]
   [ai.miniforge.tui-views.interface :as iface]
   [ai.miniforge.tui-views.msg :as msg]
   [ai.miniforge.tui-views.persistence.github :as github]
   [ai.miniforge.tui-views.persistence.pr :as persistence-pr]
   [ai.miniforge.tui-views.persistence.pr-cache :as pr-cache]
   [ai.miniforge.policy-pack.interface :as policy-pack]
   [ai.miniforge.tui-views.prompts :as prompts]))

;; ---------------------------------------------------------------------------- format-check-context

(deftest format-check-context-test
  (testing "formats single check"
    (let [result (iface/format-check-context [{:name "lint" :conclusion :success}])]
      (is (= "lint=success" result))))

  (testing "formats multiple checks comma-separated"
    (let [result (iface/format-check-context [{:name "lint" :conclusion :success}
                                              {:name "test" :conclusion :failure}])]
      (is (= "lint=success, test=failure" result))))

  (testing "uses :unknown when conclusion is missing"
    (let [result (iface/format-check-context [{:name "build"}])]
      (is (= "build=unknown" result))))

  (testing "empty checks returns empty string"
    (is (= "" (iface/format-check-context [])))))

;; ---------------------------------------------------------------------------- build-pr-context-str

(defn make-full-pr
  "Build a PR map with all fields populated."
  []
  {:pr/repo "acme/app"
   :pr/number 42
   :pr/title "Fix critical bug"
   :pr/branch "fix/critical-bug"
   :pr/author "alice"
   :pr/status :open
   :pr/ci-status :passed
   :pr/behind-main? true
   :pr/additions 50
   :pr/deletions 20
   :pr/changed-files-count 5
   :pr/ci-checks [{:name "lint" :conclusion :success}
                  {:name "test" :conclusion :success}]
   :pr/readiness {:readiness/score 85 :readiness/ready? true}
   :pr/risk {:risk/level :high
             :risk/score 0.85
             :risk/factors [{:explanation "Large change touching core module"}]}
   :pr/policy {:evaluation/passed? true
               :evaluation/packs-applied ["security" "style"]}})

(deftest build-pr-context-str-full-pr-test
  (testing "renders all fields for a fully-populated PR"
    (let [ctx (iface/build-pr-context-str (make-full-pr))]
      (is (string? ctx))
      (is (str/includes? ctx "acme/app#42"))
      (is (str/includes? ctx "Fix critical bug"))
      (is (str/includes? ctx "fix/critical-bug"))
      (is (str/includes? ctx "alice"))
      (is (str/includes? ctx "open"))
      (is (str/includes? ctx "Behind main: yes"))
      (is (str/includes? ctx "lint=success"))
      (is (str/includes? ctx "test=success"))
      (is (str/includes? ctx "+50/-20"))
      (is (str/includes? ctx "70 total lines"))
      (is (str/includes? ctx "5 files"))
      (is (str/includes? ctx "Readiness score: 85"))
      (is (str/includes? ctx "(ready)"))
      (is (str/includes? ctx "Risk level: high"))
      (is (str/includes? ctx "0.85"))
      (is (str/includes? ctx "Large change touching core module"))
      (is (str/includes? ctx "Policy: passed"))
      (is (str/includes? ctx "security"))
      (is (str/includes? ctx "style"))
      (is (str/includes? ctx "GitHub")))))

(deftest build-pr-context-str-minimal-pr-test
  (testing "renders minimal PR without optional fields"
    (let [pr {:pr/repo "acme/app" :pr/number 1 :pr/title "Small"
              :pr/branch "main" :pr/status :open}
          ctx (iface/build-pr-context-str pr)]
      (is (string? ctx))
      (is (str/includes? ctx "acme/app#1"))
      (is (str/includes? ctx "Small"))
      ;; No CI checks -> falls back to ci-status default
      (is (str/includes? ctx "CI status: unknown"))
      ;; No readiness
      (is (not (str/includes? ctx "Readiness score")))
      ;; No risk
      (is (not (str/includes? ctx "Risk level")))
      ;; No policy
      (is (not (str/includes? ctx "Policy"))))))

(deftest build-pr-context-str-nil-test
  (testing "returns nil for nil input"
    (is (nil? (iface/build-pr-context-str nil)))))

(deftest build-pr-context-str-behind-main-false-test
  (testing "shows 'no' when not behind main"
    (let [pr {:pr/repo "r" :pr/number 1 :pr/title "T"
              :pr/branch "b" :pr/behind-main? false :pr/status :open}
          ctx (iface/build-pr-context-str pr)]
      (is (str/includes? ctx "Behind main: no")))))

(deftest build-pr-context-str-failed-policy-test
  (testing "shows FAILED when policy did not pass"
    (let [pr {:pr/repo "r" :pr/number 1 :pr/title "T"
              :pr/branch "b" :pr/status :open
              :pr/policy {:evaluation/passed? false}}
          ctx (iface/build-pr-context-str pr)]
      (is (str/includes? ctx "Policy: FAILED")))))

(deftest build-pr-context-str-gitlab-provider-test
  (testing "detects GitLab provider from repo prefix"
    (let [pr {:pr/repo "gitlab:group/project" :pr/number 1 :pr/title "T"
              :pr/branch "b" :pr/status :open}
          ctx (iface/build-pr-context-str pr)]
      (is (str/includes? ctx "GitLab")))))

(deftest build-pr-context-str-zero-additions-test
  (testing "omits change size when additions and deletions are zero"
    (let [pr {:pr/repo "r" :pr/number 1 :pr/title "T"
              :pr/branch "b" :pr/status :open
              :pr/additions 0 :pr/deletions 0}
          ctx (iface/build-pr-context-str pr)]
      (is (not (str/includes? ctx "Change size"))))))

(deftest build-pr-context-str-no-author-test
  (testing "shows 'unknown' when author is nil"
    (let [pr {:pr/repo "r" :pr/number 1 :pr/title "T"
              :pr/branch "b" :pr/status :open}
          ctx (iface/build-pr-context-str pr)]
      (is (str/includes? ctx "Author: unknown")))))

(deftest build-pr-context-str-risk-without-score-test
  (testing "risk level without score omits score parenthetical"
    (let [pr {:pr/repo "r" :pr/number 1 :pr/title "T"
              :pr/branch "b" :pr/status :open
              :pr/risk {:risk/level :medium}}
          ctx (iface/build-pr-context-str pr)]
      (is (str/includes? ctx "Risk level: medium"))
      (is (not (str/includes? ctx "score:"))))))

;; ---------------------------------------------------------------------------- build-context-section

(deftest build-context-section-pr-detail-test
  (testing "renders PR detail context via prompts"
    (let [pr {:pr/repo "r" :pr/number 1 :pr/title "T" :pr/branch "b" :pr/status :open}
          result (#'iface/build-context-section {:type :pr-detail :pr pr})]
      (is (string? result))
      ;; Should contain the PR info rendered by build-pr-context-str
      (is (str/includes? result "r#1")))))

(deftest build-context-section-pr-fleet-test
  (testing "renders fleet context with selected PRs"
    (let [prs [{:pr/repo "a/b" :pr/number 1 :pr/title "First"}
               {:pr/repo "c/d" :pr/number 2 :pr/title "Second"}]
          result (#'iface/build-context-section
                  {:type :pr-fleet
                   :selected-prs prs
                   :total-prs 10
                   :active-filter :open})]
      (is (string? result))
      (is (str/includes? result "2 PR(s) selected"))))

  (testing "renders fleet context with no selected PRs"
    (let [result (#'iface/build-context-section
                  {:type :pr-fleet
                   :selected-prs []
                   :total-prs 5
                   :active-filter :all})]
      (is (str/includes? result "No PRs currently selected")))))

(deftest build-context-section-unknown-type-test
  (testing "returns unknown context for unrecognized type"
    (is (= "Unknown context."
           (#'iface/build-context-section {:type :unknown})))))

;; ---------------------------------------------------------------------------- build-chat-system-prompt

(deftest build-chat-system-prompt-test
  (testing "produces a non-empty string with context"
    (let [prompt (iface/build-chat-system-prompt
                  {:type :pr-detail
                   :pr {:pr/repo "r" :pr/number 1 :pr/title "T"
                        :pr/branch "b" :pr/status :open}
                   :max-line-width 80})]
      (is (string? prompt))
      (is (pos? (count prompt)))
      ;; Should reference max line width from template
      (is (str/includes? prompt "80")))))

;; ---------------------------------------------------------------------------- handle-chat-execute-action routing

(deftest handle-chat-execute-action-sync-test
  (testing ":sync action triggers sync-prs"
    (with-redefs [persistence-pr/load-pr-items (fn [_] {:prs [] :error nil})
                  pr-cache/read-cache          (fn [] {})
                  pr-cache/apply-cached-policy  (fn [prs _] prs)
                  pr-cache/apply-cached-agent-risk (fn [_ _] {})]
      (let [m (iface/handle-chat-execute-action {:action {:action :sync} :context {}})]
        (is (= :msg/prs-synced (first m)))))))

(deftest handle-chat-execute-action-review-with-pr-test
  (testing ":review action with PR in context"
    (with-redefs [persistence-pr/load-policy-packs (fn [] [])
                  policy-pack/evaluate-external-pr (fn [_ _] {:evaluation/passed? true})]
      (let [pr {:pr/repo "r" :pr/number 1}
            m (iface/handle-chat-execute-action
                {:action {:action :review}
                 :context {:pr pr}})]
        (is (= :msg/review-completed (first m)))))))

(deftest handle-chat-execute-action-review-no-pr-test
  (testing ":review action without PR returns failure"
    (let [m (iface/handle-chat-execute-action
              {:action {:action :review}
               :context {}})]
      (is (= :msg/chat-action-result (first m)))
      (is (false? (:success? (second m)))))))

(deftest handle-chat-execute-action-open-with-url-test
  (testing ":open action with URL in context"
    (let [opened (atom nil)]
      (with-redefs [clojure.java.browse/browse-url (fn [url] (reset! opened url))]
        (let [m (iface/handle-chat-execute-action
                  {:action {:action :open}
                   :context {:pr {:pr/url "https://github.com/r/r/pull/1"}}})]
          (is (= :msg/chat-action-result (first m)))
          (is (true? (:success? (second m)))))))))

(deftest handle-chat-execute-action-open-no-url-test
  (testing ":open action without URL returns failure"
    (let [m (iface/handle-chat-execute-action
              {:action {:action :open}
               :context {:pr {}}})]
      (is (= :msg/chat-action-result (first m)))
      (is (false? (:success? (second m)))))))

(deftest handle-chat-execute-action-unknown-test
  (testing "unknown action type returns failure"
    (let [m (iface/handle-chat-execute-action
              {:action {:action :foobar}
               :context {}})]
      (is (= :msg/chat-action-result (first m)))
      (is (false? (:success? (second m))))
      (is (str/includes? (:message (second m)) "Unknown action")))))

(deftest handle-chat-execute-action-exception-test
  (testing "exception is caught and returned as chat-action-result"
    (with-redefs [persistence-pr/load-policy-packs (fn [] (throw (Exception. "boom")))]
      (let [m (iface/handle-chat-execute-action
                {:action {:action :review}
                 :context {:pr {:pr/repo "r" :pr/number 1}}})]
        (is (= :msg/chat-action-result (first m)))
        (is (false? (:success? (second m))))
        (is (str/includes? (:message (second m)) "boom"))))))

(deftest handle-chat-execute-action-decompose-test
  (testing ":decompose action returns decomposition-started"
    (let [pr {:pr/repo "r" :pr/number 1}
          m (iface/handle-chat-execute-action
              {:action {:action :decompose}
               :context {:pr pr}})]
      (is (= :msg/decomposition-started (first m))))))

(deftest handle-chat-execute-action-evaluate-test
  (testing ":evaluate action with PR triggers policy evaluation"
    (with-redefs [persistence-pr/load-policy-packs (fn [] [])
                  policy-pack/evaluate-external-pr (fn [_ _] {:evaluation/passed? true})]
      (let [pr {:pr/repo "r" :pr/number 1}
            m (iface/handle-chat-execute-action
                {:action {:action :evaluate}
                 :context {:pr pr}})]
        (is (= :msg/policy-evaluated (first m)))))))

;; ---------------------------------------------------------------------------- dispatch-effect routing

(deftest dispatch-effect-routes-fetch-pr-diff-test
  (testing ":fetch-pr-diff routes to handle-fetch-pr-diff"
    (with-redefs [github/fetch-pr-diff-and-detail
                  (fn [repo number]
                    {:diff "d" :detail {:title "T"} :repo repo :number number})]
      (let [m (iface/dispatch-effect nil {:type :fetch-pr-diff :repo "r" :number 1})]
        (is (= :msg/pr-diff-fetched (first m)))))))

(deftest dispatch-effect-routes-sync-prs-test
  (testing ":sync-prs routes to handle-sync-prs"
    (with-redefs [persistence-pr/load-pr-items (fn [_] {:prs [] :error nil})
                  pr-cache/read-cache          (fn [] {})
                  pr-cache/apply-cached-policy  (fn [prs _] prs)
                  pr-cache/apply-cached-agent-risk (fn [_ _] {})]
      (let [m (iface/dispatch-effect nil {:type :sync-prs})]
        (is (= :msg/prs-synced (first m)))))))

(deftest dispatch-effect-unknown-type-test
  (testing "unknown effect type returns nil"
    (is (nil? (iface/dispatch-effect nil {:type :unknown-effect})))))

(deftest dispatch-effect-open-url-test
  (testing ":open-url with nil url returns nil"
    (is (nil? (iface/dispatch-effect nil {:type :open-url :url nil})))))

;; ---------------------------------------------------------------------------- parse-actions edge cases

(deftest parse-actions-multiple-test
  (testing "extracts multiple actions from text"
    (let [text (str "Analysis complete.\n"
                    "[ACTION: review | Review PR | Run policy packs]\n"
                    "Also consider:\n"
                    "[ACTION: remediate | Fix issues | Auto-fix violations]")
          [clean actions] (iface/parse-actions text)]
      (is (= 2 (count actions)))
      (is (= :review (:action (first actions))))
      (is (= :remediate (:action (second actions))))
      (is (not (str/includes? clean "[ACTION:"))))))

(deftest parse-actions-preserves-surrounding-text-test
  (testing "clean content preserves non-action text"
    (let [[clean _] (iface/parse-actions "Before.\n[ACTION: x | Y | Z]\nAfter.")]
      (is (str/includes? clean "Before."))
      (is (str/includes? clean "After.")))))

;; ---------------------------------------------------------------------------- parse-risk-line edge cases

(deftest parse-risk-line-level-case-insensitive-test
  (testing "level is lowercased"
    (let [r (iface/parse-risk-line "RISK: owner/repo#1 | HIGH | reason")]
      (is (= "high" (:level r)))))

  (testing "mixed case level"
    (let [r (iface/parse-risk-line "RISK: owner/repo#1 | Medium | reason")]
      (is (= "medium" (:level r))))))

(deftest parse-risk-line-non-numeric-pr-number-test
  (testing "returns nil when PR number is not numeric"
    (is (nil? (iface/parse-risk-line "RISK: owner/repo#abc | high | reason")))))

(deftest parse-risk-line-repo-with-org-slashes-test
  (testing "handles repo slug with owner/repo format"
    (let [r (iface/parse-risk-line "RISK: my-org/my-repo#100 | low | trivial")]
      (is (= ["my-org/my-repo" 100] (:id r))))))

;; ---------------------------------------------------------------------------- format-pr-summary-line edge cases

(deftest format-pr-summary-line-with-special-chars-test
  (testing "handles title with special characters"
    (let [pr {:pr/repo "r" :pr/number 1 :pr/title "Fix: handle [brackets] & 'quotes'"}
          result (iface/format-pr-summary-line pr)]
      (is (= "- r#1 Fix: handle [brackets] & 'quotes'" result)))))

;; ---------------------------------------------------------------------------- chat-msg->llm-msg

(deftest chat-msg->llm-msg-assistant-test
  (testing "converts assistant role"
    (let [result (iface/chat-msg->llm-msg {:role :assistant :content "hi"})]
      (is (= "assistant" (:role result)))
      (is (= "hi" (:content result))))))
