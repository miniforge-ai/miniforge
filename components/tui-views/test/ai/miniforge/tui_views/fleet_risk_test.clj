(ns ai.miniforge.tui-views.fleet-risk-test
  "Tests for fleet risk triage, PR-workflow linkage, and extracted helpers."
  (:require
   [clojure.test :refer [deftest testing is are]]
   [ai.miniforge.tui-views.model :as model]
   [ai.miniforge.tui-views.update.events :as events]
   [ai.miniforge.tui-views.view.project :as project]
   [ai.miniforge.tui-views.prompts :as prompts]
   [ai.miniforge.tui-views.schema :as schema]))

;; ---------------------------------------------------------------------------- Helpers

(defn fresh [] (model/init-model))

(defn make-pr
  "Build a minimal PR map."
  [repo number & {:keys [title status ci-status additions deletions workflow-id]
                  :or {title "Test PR" status :open ci-status :passed
                       additions 10 deletions 5}}]
  (cond-> {:pr/repo repo :pr/number number :pr/title title
           :pr/status status :pr/ci-status ci-status
           :pr/additions additions :pr/deletions deletions}
    workflow-id (assoc :pr/workflow-id workflow-id)))

;; ---------------------------------------------------------------------------- repo-from-pr-url

(deftest repo-from-pr-url-test
  (testing "extracts owner/repo from GitHub PR URL"
    (is (= "owner/repo"
           (events/repo-from-pr-url "https://github.com/owner/repo/pull/123"))))
  (testing "returns nil for non-GitHub URLs"
    (is (nil? (events/repo-from-pr-url "https://gitlab.com/group/project/-/merge_requests/42"))))
  (testing "returns nil for nil"
    (is (nil? (events/repo-from-pr-url nil)))))

;; ---------------------------------------------------------------------------- index-workflow-pr

(deftest index-workflow-pr-test
  (let [wf-id (random-uuid)]
    (testing "indexes when pr-info has number and url"
      (let [m (events/index-workflow-pr (fresh) wf-id
                                         {:pr-number 42
                                          :pr-url "https://github.com/acme/app/pull/42"})]
        (is (= wf-id (get-in m [:workflow-pr-index ["acme/app" 42]])))))

    (testing "no-op when pr-info is nil"
      (let [m (events/index-workflow-pr (fresh) wf-id nil)]
        (is (empty? (:workflow-pr-index m)))))

    (testing "no-op when url can't be parsed"
      (let [m (events/index-workflow-pr (fresh) wf-id
                                         {:pr-number 1 :pr-url "not-a-url"})]
        (is (empty? (:workflow-pr-index m)))))))

;; ---------------------------------------------------------------------------- annotate-pr-with-workflow

(deftest annotate-pr-with-workflow-test
  (let [wf-id (random-uuid)
        idx {["acme/app" 42] wf-id}]
    (testing "adds :pr/workflow-id when matched"
      (let [pr (events/annotate-pr-with-workflow idx (make-pr "acme/app" 42))]
        (is (= wf-id (:pr/workflow-id pr)))))

    (testing "leaves PR unchanged when no match"
      (let [pr (make-pr "acme/app" 99)
            result (events/annotate-pr-with-workflow idx pr)]
        (is (nil? (:pr/workflow-id result)))
        (is (= pr result))))))

;; ---------------------------------------------------------------------------- handle-workflow-done with pr-info

(deftest handle-workflow-done-stores-pr-info-test
  (let [wf-id (random-uuid)
        pr-info {:pr-number 42
                 :pr-url "https://github.com/acme/app/pull/42"
                 :branch "feat/thing"
                 :commit-sha "abc123"}
        m (-> (fresh)
              (events/handle-workflow-added {:workflow-id wf-id :name "test" :spec nil})
              (events/handle-workflow-done {:workflow-id wf-id :status :success
                                            :pr-info pr-info}))]
    (testing "stores pr-info on workflow row"
      (is (= pr-info (get-in m [:workflows 0 :pr-info]))))

    (testing "builds reverse index"
      (is (= wf-id (get-in m [:workflow-pr-index ["acme/app" 42]]))))))

;; ---------------------------------------------------------------------------- handle-prs-synced annotates PRs

(deftest handle-prs-synced-annotates-workflow-linked-prs-test
  (let [wf-id (random-uuid)
        m (-> (fresh)
              (assoc :workflow-pr-index {["acme/app" 42] wf-id})
              (events/handle-prs-synced {:pr-items [(make-pr "acme/app" 42)
                                                     (make-pr "other/repo" 1)]}))]
    (testing "linked PR gets :pr/workflow-id"
      (let [linked (first (filter #(= 42 (:pr/number %)) (:pr-items m)))]
        (is (= wf-id (:pr/workflow-id linked)))))

    (testing "unlinked PR has no :pr/workflow-id"
      (let [unlinked (first (filter #(= 1 (:pr/number %)) (:pr-items m)))]
        (is (nil? (:pr/workflow-id unlinked)))))))

;; ---------------------------------------------------------------------------- assessments->risk-map

(deftest assessments->risk-map-test
  (testing "converts LLM assessments to risk map"
    (let [assessments [{:id ["acme/app" 42] :level "high" :reason "Large change"}
                       {:id ["other/repo" 1] :level "low" :reason "Small fix"}]
          result (events/assessments->risk-map assessments)]
      (is (= {:level :high :reason "Large change"}
             (get result ["acme/app" 42])))
      (is (= {:level :low :reason "Small fix"}
             (get result ["other/repo" 1]))))))

;; ---------------------------------------------------------------------------- handle-fleet-risk-triaged

(deftest handle-fleet-risk-triaged-test
  (testing "stores assessments as agent-risk"
    (let [m (events/handle-fleet-risk-triaged
              (fresh)
              {:assessments [{:id ["a/b" 1] :level "high" :reason "Big"}]})]
      (is (= {:level :high :reason "Big"} (get-in m [:agent-risk ["a/b" 1]])))))

  (testing "sets flash-message on error"
    (let [m (events/handle-fleet-risk-triaged (fresh) {:error "fail"})]
      (is (= "Risk triage: fail" (:flash-message m))))))

;; ---------------------------------------------------------------------------- build-pr-summary-for-triage

(deftest build-pr-summary-for-triage-test
  (testing "includes repo, number, title, status, ci"
    (let [s (events/build-pr-summary-for-triage (make-pr "acme/app" 42))]
      (is (re-find #"acme/app#42" s))
      (is (re-find #"Test PR" s))
      (is (re-find #"open" s))
      (is (re-find #"ci:passed" s))))

  (testing "includes miniforge-sourced tag when linked"
    (let [s (events/build-pr-summary-for-triage
              (make-pr "acme/app" 42 :workflow-id (random-uuid)))]
      (is (re-find #"miniforge-sourced" s)))))

;; ---------------------------------------------------------------------------- pr-triage-summary

(deftest pr-triage-summary-test
  (let [pr (make-pr "acme/app" 42)
        result (events/pr-triage-summary pr)]
    (is (= ["acme/app" 42] (:id result)))
    (is (string? (:summary result)))))

;; ---------------------------------------------------------------------------- find-linked-workflow (project.clj)

(deftest find-linked-workflow-test
  (let [wf-id (random-uuid)
        wfs [{:id wf-id :name "feat-thing" :status :running}
             {:id (random-uuid) :name "other" :status :running}]]
    (testing "direct lookup by workflow-id"
      (is (= wf-id (:id (project/find-linked-workflow wfs wf-id nil)))))

    (testing "branch name match"
      (is (= wf-id (:id (project/find-linked-workflow wfs nil "feat-thing-extra")))))

    (testing "returns nil when no match"
      (is (nil? (project/find-linked-workflow wfs nil "unrelated-branch"))))))

;; ---------------------------------------------------------------------------- find-workflow-by-id (project.clj)

(deftest find-workflow-by-id-test
  (let [id1 (random-uuid)
        id2 (random-uuid)
        wfs [{:id id1 :name "a"} {:id id2 :name "b"}]]
    (is (= "a" (:name (project/find-workflow-by-id wfs id1))))
    (is (= "b" (:name (project/find-workflow-by-id wfs id2))))
    (is (nil? (project/find-workflow-by-id wfs (random-uuid))))))

;; ---------------------------------------------------------------------------- workflow-matches-branch?

(deftest workflow-matches-branch-test
  (testing "matches when branch contains workflow name"
    (is (project/workflow-matches-branch? {:name "feat-x"} "feat-x-impl")))
  (testing "matches when workflow name contains branch"
    (is (project/workflow-matches-branch? {:name "feat-x-long"} "feat-x")))
  (testing "no match when unrelated"
    (is (not (project/workflow-matches-branch? {:name "feat-x"} "bugfix-y"))))
  (testing "false when name is nil"
    (is (not (project/workflow-matches-branch? {:name nil} "branch")))))

;; ---------------------------------------------------------------------------- Prompt templates

(deftest prompt-templates-load-test
  (testing "chat system prompt template exists"
    (is (some? (prompts/get-template :chat/system))))
  (testing "fleet triage template exists"
    (is (some? (prompts/get-template :fleet-triage/system))))
  (testing "render substitutes variables"
    (let [result (prompts/render :chat/system
                                {:max-line-width 50
                                 :context "test context"})]
      (is (re-find #"50 characters wide" result))
      (is (re-find #"test context" result))
      (is (not (re-find #"\{\{" result))))))

;; ---------------------------------------------------------------------------- Schema validation

(deftest schema-defs-exist-test
  (testing "all schema defs are non-nil"
    (are [s] (some? s)
      schema/AgentRiskAssessment
      schema/AgentRiskMap
      schema/WorkflowPrInfo
      schema/WorkflowPrIndex
      schema/ChatAction
      schema/ChatMessage
      schema/ChatState
      schema/WorkflowDetail
      schema/TriageSummary
      schema/WorkflowSummary)))
