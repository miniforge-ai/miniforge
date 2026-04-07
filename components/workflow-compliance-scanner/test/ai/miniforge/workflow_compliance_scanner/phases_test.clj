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

(ns ai.miniforge.workflow-compliance-scanner.phases-test
  "Unit tests for the compliance scan workflow phase interceptors.

   Tests all three pure-code phases:
     :compliance-scan     — scan, store result
     :compliance-classify — classify violations, store classified
     :compliance-plan     — plan, write files, store plan output"
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.compliance-scanner.interface           :as compliance-scanner]
   [ai.miniforge.phase.registry                         :as registry]
   [ai.miniforge.workflow-compliance-scanner.phases     :as phases]
   [ai.miniforge.workflow-compliance-scanner.interface]))

;------------------------------------------------------------------------------ Test Fixtures

(def stub-violation
  {:rule/id       :std/clojure
   :rule/category "210"
   :rule/title    "Clojure Map Access"
   :file          "components/foo/src/core.clj"
   :line          10
   :current       "(or (:k m) nil)"
   :suggested     "(get m :k nil)"})

(def stub-classified-violation
  (assoc stub-violation :auto-fixable? true :rationale "Literal default"))

(def stub-scan-result
  {:violations    [stub-violation]
   :rules-scanned [:std/clojure]
   :files-scanned 1
   :duration-ms   42})

(def stub-plan
  {:dag-tasks [{:task/id (random-uuid) :task/deps #{} :task/file "components/foo/src/core.clj"
                :task/rule-id :std/clojure :task/violations [stub-classified-violation]}]
   :work-spec  "# Compliance Work Spec\n"
   :summary    {:total-violations 1 :auto-fixable 1 :needs-review 0
                :files-affected 1 :rules-violated 1}})

(defn base-ctx
  "Minimal execution context for testing."
  []
  {:execution/id            (random-uuid)
   :execution/worktree-path "/tmp/test-repo"
   :execution/input         {:repo-path      "/tmp/test-repo"
                              :standards-path ".standards"
                              :rules          :always-apply}
   :execution/metrics       {:duration-ms 0}
   :execution/phase-results {}})

;------------------------------------------------------------------------------ Registry Tests

(deftest phases-registered-in-registry-test
  (testing "all three compliance phases are registered in the registry after namespace load"
    (is (some? (registry/phase-defaults :compliance-scan))
        ":compliance-scan defaults should be registered")
    (is (some? (registry/phase-defaults :compliance-classify))
        ":compliance-classify defaults should be registered")
    (is (some? (registry/phase-defaults :compliance-plan))
        ":compliance-plan defaults should be registered")))

(deftest phase-default-budgets-test
  (testing ":compliance-scan has correct default budget"
    (let [defaults (registry/phase-defaults :compliance-scan)]
      (is (= 5000 (get-in defaults [:budget :tokens])))
      (is (= 1 (get-in defaults [:budget :iterations])))
      (is (= 300 (get-in defaults [:budget :time-seconds])))))
  (testing ":compliance-classify has correct default budget"
    (let [defaults (registry/phase-defaults :compliance-classify)]
      (is (= 1000 (get-in defaults [:budget :tokens])))
      (is (= 1 (get-in defaults [:budget :iterations])))
      (is (= 60 (get-in defaults [:budget :time-seconds])))))
  (testing ":compliance-plan has correct default budget"
    (let [defaults (registry/phase-defaults :compliance-plan)]
      (is (= 5000 (get-in defaults [:budget :tokens])))
      (is (= 1 (get-in defaults [:budget :iterations])))
      (is (= 120 (get-in defaults [:budget :time-seconds]))))))

;------------------------------------------------------------------------------ :compliance-scan Tests

(deftest enter-compliance-scan-stores-scan-result-test
  (testing "enter-compliance-scan stores scan result under [:phase :result :output]"
    (with-redefs [compliance-scanner/scan (fn [_repo _standards _opts] stub-scan-result)]
      (let [ctx    (base-ctx)
            result (phases/enter-compliance-scan ctx)]
        (is (= :compliance-scan (get-in result [:phase :name]))
            "Phase name should be :compliance-scan")
        (is (= :running (get-in result [:phase :status]))
            "Phase status should be :running after enter")
        (is (= stub-scan-result (get-in result [:phase :result :output]))
            "Scan result should be stored in [:phase :result :output]")
        (is (= :success (get-in result [:phase :result :status]))
            "Result status should be :success")))))

(deftest enter-compliance-scan-uses-worktree-path-test
  (testing "enter-compliance-scan prefers :execution/worktree-path for repo-path"
    (let [captured-repo (atom nil)]
      (with-redefs [compliance-scanner/scan (fn [repo _standards _opts]
                                              (reset! captured-repo repo)
                                              stub-scan-result)]
        (phases/enter-compliance-scan (base-ctx))
        (is (= "/tmp/test-repo" @captured-repo)
            "Should use :execution/worktree-path as repo-path")))))

(deftest leave-compliance-scan-records-violation-count-test
  (testing "leave-compliance-scan adds violation-count to metrics and stores phase results"
    (with-redefs [compliance-scanner/scan (fn [_repo _standards _opts] stub-scan-result)]
      (let [ctx         (base-ctx)
            entered-ctx (phases/enter-compliance-scan ctx)
            left-ctx    (phases/leave-compliance-scan entered-ctx)]
        (is (= :completed (get-in left-ctx [:phase :status]))
            "Phase status should be :completed after leave")
        (is (= 1 (get-in left-ctx [:phase :metrics :violation-count]))
            "Violation count should be recorded in metrics")
        (is (number? (get-in left-ctx [:phase :duration-ms]))
            "Duration should be recorded")
        (is (= stub-scan-result
               (get-in left-ctx [:execution/phase-results :compliance-scan :result :output]))
            "Scan result should be promoted to :execution/phase-results")
        (is (= [:compliance-scan] (get-in left-ctx [:execution :phases-completed]))
            ":compliance-scan should be added to phases-completed")))))

;------------------------------------------------------------------------------ :compliance-classify Tests

(deftest enter-compliance-classify-reads-violations-and-stores-classified-test
  (testing "enter-compliance-classify reads violations from phase-results and stores classified"
    (with-redefs [compliance-scanner/classify (fn [_violations] [stub-classified-violation])]
      (let [ctx    (-> (base-ctx)
                       (assoc-in [:execution/phase-results :compliance-scan :result :output]
                                 stub-scan-result))
            result (phases/enter-compliance-classify ctx)]
        (is (= :compliance-classify (get-in result [:phase :name]))
            "Phase name should be :compliance-classify")
        (is (= :running (get-in result [:phase :status]))
            "Phase status should be :running after enter")
        (is (= [stub-classified-violation]
               (get-in result [:phase :result :output :classified-violations]))
            "Classified violations should be stored in output")))))

(deftest enter-compliance-classify-handles-missing-scan-results-test
  (testing "enter-compliance-classify uses empty violations when scan results missing"
    (let [classify-input (atom nil)]
      (with-redefs [compliance-scanner/classify (fn [violations]
                                                  (reset! classify-input violations)
                                                  [])]
        (phases/enter-compliance-classify (base-ctx))
        (is (= [] @classify-input)
            "Should call classify with empty vector when no scan results present")))))

(deftest leave-compliance-classify-records-auto-fixable-needs-review-test
  (testing "leave-compliance-classify records auto-fixable and needs-review counts"
    (with-redefs [compliance-scanner/classify (fn [_violations] [stub-classified-violation])]
      (let [ctx        (-> (base-ctx)
                           (assoc-in [:execution/phase-results :compliance-scan :result :output]
                                     stub-scan-result))
            entered    (phases/enter-compliance-classify ctx)
            left-ctx   (phases/leave-compliance-classify entered)]
        (is (= :completed (get-in left-ctx [:phase :status]))
            "Phase status should be :completed after leave")
        (is (= 1 (get-in left-ctx [:phase :metrics :auto-fixable]))
            "Auto-fixable count should be 1")
        (is (= 0 (get-in left-ctx [:phase :metrics :needs-review]))
            "Needs-review count should be 0")
        (is (= [:compliance-classify] (get-in left-ctx [:execution :phases-completed]))
            ":compliance-classify should be added to phases-completed")))))

;------------------------------------------------------------------------------ :compliance-plan Tests

(deftest enter-compliance-plan-reads-classified-and-stores-plan-test
  (testing "enter-compliance-plan reads classified violations and stores plan output"
    (with-redefs [compliance-scanner/plan              (fn [_classified _repo] stub-plan)
                  compliance-scanner/write-work-spec!  (fn [_spec _repo] "/tmp/test-repo/docs/compliance/spec.md")
                  compliance-scanner/write-delta-report! (fn [_repo _standards _classified _plan] "/tmp/test-repo/.miniforge/compliance-report.edn")]
      (let [ctx    (-> (base-ctx)
                       (assoc-in [:execution/phase-results :compliance-classify :result :output :classified-violations]
                                 [stub-classified-violation]))
            result (phases/enter-compliance-plan ctx)]
        (is (= :compliance-plan (get-in result [:phase :name]))
            "Phase name should be :compliance-plan")
        (is (= :running (get-in result [:phase :status]))
            "Phase status should be :running after enter")
        (is (= stub-plan (get-in result [:phase :result :output :plan]))
            "Plan should be stored in output")
        (is (= 1 (get-in result [:phase :result :output :task-count]))
            "Task count should reflect dag-tasks count")))))

(deftest enter-compliance-plan-calls-write-work-spec-test
  (testing "enter-compliance-plan calls write-work-spec! with the work-spec string"
    (let [write-spec-calls (atom [])]
      (with-redefs [compliance-scanner/plan              (fn [_classified _repo] stub-plan)
                    compliance-scanner/write-work-spec!  (fn [spec repo]
                                                           (swap! write-spec-calls conj {:spec spec :repo repo})
                                                           "/written/path")
                    compliance-scanner/write-delta-report! (fn [_repo _standards _classified _plan] "/written/report")]
        (phases/enter-compliance-plan (base-ctx))
        (is (= 1 (count @write-spec-calls))
            "write-work-spec! should be called once")
        (is (= "# Compliance Work Spec\n" (:spec (first @write-spec-calls)))
            "Work spec string should be passed to write-work-spec!")))))

(deftest enter-compliance-plan-calls-write-delta-report-test
  (testing "enter-compliance-plan calls write-delta-report! with correct args"
    (let [write-report-calls (atom [])]
      (with-redefs [compliance-scanner/plan              (fn [_classified _repo] stub-plan)
                    compliance-scanner/write-work-spec!  (fn [_spec _repo] "/written/spec")
                    compliance-scanner/write-delta-report! (fn [repo standards classified plan]
                                                             (swap! write-report-calls conj
                                                                    {:repo repo :standards standards
                                                                     :classified classified :plan plan})
                                                             "/written/report")]
        (let [ctx (-> (base-ctx)
                      (assoc-in [:execution/phase-results :compliance-classify :result :output :classified-violations]
                                [stub-classified-violation]))]
          (phases/enter-compliance-plan ctx)
          (is (= 1 (count @write-report-calls))
              "write-delta-report! should be called once")
          (is (= "/tmp/test-repo" (:repo (first @write-report-calls)))
              "repo-path should be passed correctly")
          (is (= [stub-classified-violation] (:classified (first @write-report-calls)))
              "Classified violations should be passed to write-delta-report!"))))))

(deftest leave-compliance-plan-records-task-count-test
  (testing "leave-compliance-plan records task count in metrics"
    (with-redefs [compliance-scanner/plan              (fn [_classified _repo] stub-plan)
                  compliance-scanner/write-work-spec!  (fn [_spec _repo] "/written/spec")
                  compliance-scanner/write-delta-report! (fn [_repo _standards _classified _plan] "/written/report")]
      (let [ctx      (-> (base-ctx)
                         (assoc-in [:execution/phase-results :compliance-classify :result :output :classified-violations]
                                   [stub-classified-violation]))
            entered  (phases/enter-compliance-plan ctx)
            left-ctx (phases/leave-compliance-plan entered)]
        (is (= :completed (get-in left-ctx [:phase :status]))
            "Phase status should be :completed after leave")
        (is (= 1 (get-in left-ctx [:phase :metrics :task-count]))
            "Task count should be recorded in metrics")
        (is (= [:compliance-plan] (get-in left-ctx [:execution :phases-completed]))
            ":compliance-plan should be added to phases-completed")))))

;------------------------------------------------------------------------------ :compliance-execute Tests

(deftest phases-execute-registered-in-registry-test
  (testing ":compliance-execute defaults are registered"
    (is (some? (registry/phase-defaults :compliance-execute)))))

(deftest phase-execute-default-budget-test
  (testing ":compliance-execute has correct default budget"
    (let [defaults (registry/phase-defaults :compliance-execute)]
      (is (= 5000 (get-in defaults [:budget :tokens])))
      (is (= 1 (get-in defaults [:budget :iterations])))
      (is (= 1800 (get-in defaults [:budget :time-seconds]))))))

(deftest enter-compliance-execute-stores-execute-result-test
  (testing "enter-compliance-execute calls execute! and stores output"
    (let [stub-exec-result {:prs [{:rule/id :std/clojure :branch "fix/compliance-210" :pr-url "https://github.com/org/repo/pull/1" :violations-fixed 5 :files-changed 3}] :violations-fixed 5 :files-changed 3}]
      (with-redefs [compliance-scanner/execute! (fn [_plan _repo] stub-exec-result)]
        (let [ctx (-> (base-ctx)
                      (assoc-in [:execution/phase-results :compliance-plan :result :output :plan] stub-plan))
              result (phases/enter-compliance-execute ctx)]
          (is (= :compliance-execute (get-in result [:phase :name])))
          (is (= :running (get-in result [:phase :status])))
          (is (= stub-exec-result (get-in result [:phase :result :output]))))))))

(deftest leave-compliance-execute-records-metrics-test
  (testing "leave-compliance-execute records metrics"
    (let [stub-exec-result {:prs [{:violations-fixed 5 :files-changed 3} {:violations-fixed 1 :files-changed 1}] :violations-fixed 6 :files-changed 4}]
      (with-redefs [compliance-scanner/execute! (fn [_plan _repo] stub-exec-result)]
        (let [ctx (-> (base-ctx)
                      (assoc-in [:execution/phase-results :compliance-plan :result :output :plan] stub-plan))
              entered (phases/enter-compliance-execute ctx)
              left-ctx (phases/leave-compliance-execute entered)]
          (is (= :completed (get-in left-ctx [:phase :status])))
          (is (= 2 (get-in left-ctx [:phase :metrics :pr-count])))
          (is (= 6 (get-in left-ctx [:phase :metrics :violations-fixed])))
          (is (= 4 (get-in left-ctx [:phase :metrics :files-changed]))))))))

(deftest error-compliance-execute-sets-failed-status-test
  (testing "error-compliance-execute sets :failed status"
    (let [ctx (base-ctx)
          ex (ex-info "Execute failed" {:reason :git-error})
          result (phases/error-compliance-execute ctx ex)]
      (is (= :failed (get-in result [:phase :status])))
      (is (= "Execute failed" (get-in result [:phase :error :message]))))))

;------------------------------------------------------------------------------ Error Handler Tests

(deftest error-compliance-scan-sets-failed-status-test
  (testing "error-compliance-scan sets :failed status with exception error"
    (let [ctx (base-ctx)
          ex  (ex-info "Scan failed" {:reason :io-error})
          result (phases/error-compliance-scan ctx ex)]
      (is (= :failed (get-in result [:phase :status]))
          "Phase status should be :failed")
      (is (= "Scan failed" (get-in result [:phase :error :message]))
          "Error message should be captured"))))

(deftest error-compliance-classify-sets-failed-status-test
  (testing "error-compliance-classify sets :failed status"
    (let [ctx (base-ctx)
          ex  (ex-info "Classify failed" {:reason :unexpected})
          result (phases/error-compliance-classify ctx ex)]
      (is (= :failed (get-in result [:phase :status]))
          "Phase status should be :failed")
      (is (some? (get-in result [:phase :error]))
          "Error map should be present"))))

(deftest error-compliance-plan-sets-failed-status-test
  (testing "error-compliance-plan sets :failed status"
    (let [ctx (base-ctx)
          ex  (ex-info "Plan failed" {:reason :write-error})
          result (phases/error-compliance-plan ctx ex)]
      (is (= :failed (get-in result [:phase :status]))
          "Phase status should be :failed")
      (is (= "Plan failed" (get-in result [:phase :error :message]))
          "Error message should be captured"))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.workflow-compliance-scanner.phases-test)
  :leave-this-here)
