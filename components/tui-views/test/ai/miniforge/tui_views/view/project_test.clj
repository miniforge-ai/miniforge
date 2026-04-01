(ns ai.miniforge.tui-views.view.project-test
  "Unit tests for extracted helper functions in project.clj."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.tui-views.view.project :as sut]
   [ai.miniforge.tui-views.view.project.helpers :as helpers]
   [ai.miniforge.tui-views.view.project.trees :as trees])
  (:import
   [java.time LocalDate ZoneId]
   [java.util Date]))

;; ============================================================================
;; readiness-state
;; ============================================================================

(deftest readiness-state-merge-ready
  (testing "Approved + CI passed + not behind → merge-ready"
    (is (= [:merge-ready 1.0]
           (sut/readiness-state :approved true false false)))))

(deftest readiness-state-behind-main
  (testing "Approved + CI passed + behind → behind-main"
    (is (= [:behind-main 0.85]
           (sut/readiness-state :approved true false true)))))

(deftest readiness-state-ci-failing-approved
  (testing "Approved + CI failed → ci-failing"
    (is (= [:ci-failing 0.5]
           (sut/readiness-state :approved false true false)))))

(deftest readiness-state-approved-pending-ci
  (testing "Approved + CI not passed and not failed → needs-review"
    (is (= [:needs-review 0.7]
           (sut/readiness-state :approved false false false)))))

(deftest readiness-state-changes-requested
  (testing "Changes requested → changes-requested"
    (is (= [:changes-requested 0.25]
           (sut/readiness-state :changes-requested false false false)))))

(deftest readiness-state-reviewing
  (testing "Reviewing → needs-review"
    (is (= [:needs-review 0.5]
           (sut/readiness-state :reviewing false false false)))))

(deftest readiness-state-open-ci-fail
  (testing "Open + CI failed → ci-failing"
    (is (= [:ci-failing 0.25]
           (sut/readiness-state :open false true false)))))

(deftest readiness-state-open
  (testing "Open + no CI failure → needs-review"
    (is (= [:needs-review 0.4]
           (sut/readiness-state :open false false false)))))

(deftest readiness-state-draft
  (testing "Draft → draft"
    (is (= [:draft 0.1]
           (sut/readiness-state :draft false false false)))))

(deftest readiness-state-merged
  (testing "Merged → merged with score 1.0"
    (is (= [:merged 1.0]
           (sut/readiness-state :merged false false false)))))

(deftest readiness-state-closed
  (testing "Closed → closed with score 0.0"
    (is (= [:closed 0.0]
           (sut/readiness-state :closed false false false)))))

(deftest readiness-state-unknown
  (testing "Unknown status → unknown"
    (is (= [:unknown 0.0]
           (sut/readiness-state :something-else false false false)))))

;; ============================================================================
;; readiness-blockers
;; ============================================================================

(deftest readiness-blockers-ci-fail
  (testing "CI failure adds a CI blocker"
    (let [blockers (sut/readiness-blockers :approved true false)]
      (is (= 1 (count blockers)))
      (is (= :ci (-> blockers first :blocker/type))))))

(deftest readiness-blockers-behind
  (testing "Behind main adds a behind-main blocker"
    (let [blockers (sut/readiness-blockers :approved false true)]
      (is (some #(= :behind-main (:blocker/type %)) blockers)))))

(deftest readiness-blockers-review-needed
  (testing "Open/reviewing statuses add review blocker"
    (doseq [status [:open :reviewing]]
      (let [blockers (sut/readiness-blockers status false false)]
        (is (some #(= :review (:blocker/type %)) blockers)
            (str status " should produce review blocker"))))))

(deftest readiness-blockers-changes-requested
  (testing "Changes requested adds review blocker"
    (let [blockers (sut/readiness-blockers :changes-requested false false)]
      (is (some #(= :review (:blocker/type %)) blockers))
      (is (some #(= "Reviewer requested changes" (:blocker/message %)) blockers)))))

(deftest readiness-blockers-draft
  (testing "Draft adds review blocker with author source"
    (let [blockers (sut/readiness-blockers :draft false false)]
      (is (some #(and (= :review (:blocker/type %))
                      (= "author" (:blocker/source %)))
                blockers)))))

(deftest readiness-blockers-approved-no-issues
  (testing "Approved with no CI fail and not behind → empty blockers"
    (is (empty? (sut/readiness-blockers :approved false false)))))

(deftest readiness-blockers-multiple
  (testing "Multiple conditions produce multiple blockers"
    (let [blockers (sut/readiness-blockers :open true true)]
      (is (>= (count blockers) 3)))))

;; ============================================================================
;; readiness-factors
;; ============================================================================

(deftest readiness-factors-all-green
  (testing "All green → weighted score near 1.0"
    (let [result (sut/readiness-factors :approved true false false)]
      (is (>= (:weighted result) 0.85))
      (is (= 1.0 (:ci-score result)))
      (is (= 1.0 (:review-score result)))
      (is (= 1.0 (:behind-score result)))
      (is (= 5 (count (:factors result)))))))

(deftest readiness-factors-ci-failed
  (testing "CI failed → ci-score 0.0"
    (let [result (sut/readiness-factors :approved false true false)]
      (is (= 0.0 (:ci-score result))))))

(deftest readiness-factors-draft
  (testing "Draft → review-score 0.0"
    (let [result (sut/readiness-factors :draft false false false)]
      (is (= 0.0 (:review-score result))))))

(deftest readiness-factors-behind
  (testing "Behind main → behind-score 0.0"
    (let [result (sut/readiness-factors :approved true false true)]
      (is (= 0.0 (:behind-score result))))))

(deftest readiness-factors-weights-sum-to-one
  (testing "Factor weights sum to 1.0"
    (let [result (sut/readiness-factors :approved true false false)
          total (reduce + 0.0 (map :weight (:factors result)))]
      (is (< (abs (- 1.0 total)) 0.001)))))

;; ============================================================================
;; ci-section-nodes
;; ============================================================================

(deftest ci-section-nodes-passed
  (testing "Passed CI produces green header"
    (let [nodes (sut/ci-section-nodes :passed [])]
      (is (= 1 (count nodes)))
      (is (= sut/status-pass (:fg (first nodes))))
      (is (.contains (:label (first nodes)) "passed")))))

(deftest ci-section-nodes-failed
  (testing "Failed CI produces red header"
    (let [nodes (sut/ci-section-nodes :failed [])]
      (is (= sut/status-fail (:fg (first nodes)))))))

(deftest ci-section-nodes-with-checks
  (testing "Individual checks are appended as child nodes"
    (let [checks [{:name "lint" :conclusion :success}
                  {:name "test" :conclusion :failure}]
          nodes (sut/ci-section-nodes :failed checks)]
      (is (= 3 (count nodes)))
      (is (= 2 (:depth (second nodes)))))))

;; ============================================================================
;; behind-main-node
;; ============================================================================

(deftest behind-main-node-behind
  (testing "Behind → red node with merge state"
    (let [node (sut/behind-main-node true "DIRTY")]
      (is (.contains (:label node) "yes"))
      (is (.contains (:label node) "DIRTY"))
      (is (= sut/status-fail (:fg node))))))

(deftest behind-main-node-not-behind
  (testing "Not behind → green node"
    (let [node (sut/behind-main-node false nil)]
      (is (.contains (:label node) "no"))
      (is (= sut/status-pass (:fg node))))))

;; ============================================================================
;; review-node
;; ============================================================================

(deftest review-node-approved
  (testing "Approved → green node"
    (let [node (sut/review-node :approved)]
      (is (.contains (:label node) "approved"))
      (is (= sut/status-pass (:fg node))))))

(deftest review-node-changes-requested
  (testing "Changes requested → red node"
    (let [node (sut/review-node :changes-requested)]
      (is (.contains (:label node) "changes requested"))
      (is (= sut/status-fail (:fg node))))))

(deftest review-node-reviewing
  (testing "Reviewing → yellow node"
    (let [node (sut/review-node :reviewing)]
      (is (.contains (:label node) "review required"))
      (is (= sut/status-warning (:fg node))))))

(deftest review-node-draft
  (testing "Draft → no color"
    (let [node (sut/review-node :draft)]
      (is (.contains (:label node) "draft"))
      (is (nil? (:fg node))))))

(deftest review-node-default
  (testing "Unknown status → pending with yellow"
    (let [node (sut/review-node :unknown)]
      (is (.contains (:label node) "pending"))
      (is (= sut/status-warning (:fg node))))))

;; ============================================================================
;; gates-section-nodes
;; ============================================================================

(deftest gates-section-nodes-empty
  (testing "No gates → single 'none' node"
    (let [nodes (sut/gates-section-nodes [])]
      (is (= 1 (count nodes)))
      (is (.contains (:label (first nodes)) "none")))))

(deftest gates-section-nodes-all-passed
  (testing "All gates passed → green header + child nodes"
    (let [gates [{:gate/id :lint :gate/passed? true}
                 {:gate/id :test :gate/passed? true}]
          nodes (sut/gates-section-nodes gates)]
      (is (= 3 (count nodes)))
      (is (= sut/status-pass (:fg (first nodes))))
      (is (.contains (:label (first nodes)) "2/2 passed")))))

(deftest gates-section-nodes-some-failed
  (testing "Some gates failed → yellow header + mixed children"
    (let [gates [{:gate/id :lint :gate/passed? true}
                 {:gate/id :test :gate/passed? false}]
          nodes (sut/gates-section-nodes gates)]
      (is (= 3 (count nodes)))
      (is (= sut/status-warning (:fg (first nodes))))
      (is (.contains (:label (first nodes)) "1/2 passed"))
      (is (= sut/status-pass (:fg (second nodes))))
      (is (= sut/status-fail (:fg (nth nodes 2)))))))

;; ============================================================================
;; packs-applied-nodes
;; ============================================================================

(deftest packs-applied-nodes-empty
  (testing "Empty packs → nil"
    (is (nil? (sut/packs-applied-nodes [])))))

(deftest packs-applied-nodes-present
  (testing "Non-empty packs → header + child per pack"
    (let [nodes (sut/packs-applied-nodes ["pack-a" "pack-b"])]
      (is (= 3 (count nodes)))
      (is (.contains (:label (first nodes)) "2")))))

;; ============================================================================
;; severity-summary-nodes
;; ============================================================================

(deftest severity-summary-nodes-nil
  (testing "Nil summary → nil"
    (is (nil? (sut/severity-summary-nodes nil)))))

(deftest severity-summary-nodes-all-zero
  (testing "All-zero summary → nil"
    (is (nil? (sut/severity-summary-nodes {:critical 0 :major 0 :minor 0 :info 0})))))

(deftest severity-summary-nodes-mixed
  (testing "Mixed counts → summary string"
    (let [nodes (sut/severity-summary-nodes {:critical 1 :major 0 :minor 3 :info 2})]
      (is (= 1 (count nodes)))
      (let [label (:label (first nodes))]
        (is (.contains label "1 critical"))
        (is (.contains label "3 minor"))
        (is (.contains label "2 info"))
        (is (not (.contains label "major")))))))

;; ============================================================================
;; violation-nodes
;; ============================================================================

(deftest violation-nodes-empty
  (testing "Empty violations → nil"
    (is (nil? (sut/violation-nodes [])))))

(deftest violation-nodes-present
  (testing "Non-empty violations → header + colored child nodes"
    (let [violations [{:severity :critical :message "Bad thing" :auto-fixable? false}
                      {:severity :minor :message "Small thing" :auto-fixable? true}]
          nodes (sut/violation-nodes violations)]
      (is (= 3 (count nodes)))
      (is (.contains (:label (first nodes)) "2"))
      (is (= sut/status-fail (:fg (second nodes))))
      (is (.contains (:label (nth nodes 2)) "[auto-fix]")))))

;; ============================================================================
;; intent-nodes
;; ============================================================================

(deftest intent-nodes-with-description
  (testing "Evidence with intent → description label"
    (let [nodes (sut/intent-nodes {:intent {:description "Fix bug"}})]
      (is (= 2 (count nodes)))
      (is (= "Intent" (:label (first nodes))))
      (is (= "Fix bug" (:label (second nodes)))))))

(deftest intent-nodes-without-description
  (testing "No intent → fallback label"
    (let [nodes (sut/intent-nodes {})]
      (is (= "No intent data available" (:label (second nodes)))))))

;; ============================================================================
;; phase-nodes
;; ============================================================================

(deftest phase-nodes-empty
  (testing "Empty phases → just header"
    (let [nodes (sut/phase-nodes [])]
      (is (= 1 (count nodes)))
      (is (= "Phases" (:label (first nodes)))))))

(deftest phase-nodes-with-phases
  (testing "Phases render with status icons"
    (let [nodes (sut/phase-nodes [{:phase :plan :status :success}
                                  {:phase :implement :status :running}
                                  {:phase :verify :status :failed}])]
      (is (= 4 (count nodes)))
      (is (.contains (:label (second nodes)) "✓ passed"))
      (is (.contains (:label (nth nodes 2)) "● running"))
      (is (.contains (:label (nth nodes 3)) "✗ failed")))))

;; ============================================================================
;; validation-nodes
;; ============================================================================

(deftest validation-nodes-passed
  (testing "Passed validation → success label"
    (let [nodes (sut/validation-nodes {:validation {:passed? true}})]
      (is (= 2 (count nodes)))
      (is (.contains (:label (second nodes)) "All gates passed")))))

(deftest validation-nodes-failed
  (testing "Failed validation → error count"
    (let [nodes (sut/validation-nodes {:validation {:passed? false :errors [:e1 :e2]}})]
      (is (.contains (:label (second nodes)) "2 error(s)")))))

;; ============================================================================
;; policy-evidence-nodes
;; ============================================================================

(deftest policy-evidence-nodes-compliant
  (testing "Compliant policy → success label"
    (let [nodes (sut/policy-evidence-nodes {:policy {:compliant? true}})]
      (is (.contains (:label (second nodes)) "Policy compliant")))))

(deftest policy-evidence-nodes-non-compliant
  (testing "Non-compliant policy → violations label"
    (let [nodes (sut/policy-evidence-nodes {:policy {:compliant? false}})]
      (is (.contains (:label (second nodes)) "violations detected")))))

;; ============================================================================
;; derive-recommendation — regression: readiness state resolution
;; ============================================================================

(deftest recommend-not-wait-when-enriched
  (testing "PR with pr-train enrichment (no :readiness/state) produces actionable recommendation, not :wait"
    ;; Regression: explain-readiness returns {:readiness/score :readiness/ready? :readiness/factors}
    ;; but NOT :readiness/state. extract-pr-signals must derive the state from PR status/CI.
    (let [pr {:pr/status :open :pr/ci-status :passed
              :pr/additions 100 :pr/deletions 50
              :pr/readiness {:readiness/score 0.4
                             :readiness/ready? false
                             :readiness/factors []}}
          rec (sut/derive-recommendation pr)]
      (is (not= :wait (:action rec))
          "Should not be :wait — :open + ci:passed should derive :needs-review state")
      (is (= :review (:action rec))))))

(deftest recommend-merge-when-all-green
  (testing "Approved PR with passing CI, low risk, policy pass → merge"
    (let [pr {:pr/status :approved :pr/ci-status :passed
              :pr/additions 50 :pr/deletions 10
              :pr/policy {:evaluation/passed? true}
              :pr/readiness {:readiness/score 1.0
                             :readiness/ready? true
                             :readiness/factors []}}
          rec (sut/derive-recommendation pr)]
      (is (= :merge (:action rec))))))

(deftest recommend-do-not-merge-ci-failing
  (testing "Open PR with failing CI → do-not-merge"
    (let [pr {:pr/status :open :pr/ci-status :failed
              :pr/additions 100 :pr/deletions 50}
          rec (sut/derive-recommendation pr)]
      (is (= :do-not-merge (:action rec))))))

(deftest recommend-evaluate-when-ready-no-policy
  (testing "Ready PR without policy evaluation → evaluate"
    (let [pr {:pr/status :approved :pr/ci-status :passed
              :pr/additions 50 :pr/deletions 10
              :pr/readiness {:readiness/score 1.0
                             :readiness/ready? true
                             :readiness/factors []}}
          rec (sut/derive-recommendation pr)]
      (is (= :evaluate (:action rec))))))

(deftest recommend-decompose-large-pr
  (testing "Large open PR with policy evaluated → decompose"
    (let [pr {:pr/status :open :pr/ci-status :passed
              :pr/additions 400 :pr/deletions 200
              :pr/policy {:evaluation/passed? true}}
          rec (sut/derive-recommendation pr)]
      (is (= :decompose (:action rec))))))

(deftest recommend-approve-elevated-risk
  (testing "Ready PR with medium risk → approve (human sign-off)"
    (let [pr {:pr/status :approved :pr/ci-status :passed
              :pr/additions 50 :pr/deletions 10
              :pr/policy {:evaluation/passed? true}
              :pr/risk {:risk/level :medium :risk/score 0.6}
              :pr/readiness {:readiness/score 1.0
                             :readiness/ready? true
                             :readiness/factors []}}
          rec (sut/derive-recommendation pr)]
      (is (= :approve (:action rec))))))

(deftest extract-pr-signals-uses-pr-additions
  (testing "extract-pr-signals reads :pr/additions not [:change-size :additions]"
    (let [signals (sut/extract-pr-signals {:pr/status :open :pr/ci-status :passed
                                           :pr/additions 400 :pr/deletions 200})]
      (is (true? (:large? signals)) "600 LOC should be large"))))

;; ============================================================================
;; readiness-state — :merge-ready status (GitLab PRs)
;; ============================================================================

(deftest readiness-state-merge-ready-ci-passed
  (testing "merge-ready + CI passed + not behind → merge-ready"
    (is (= [:merge-ready 1.0]
           (sut/readiness-state :merge-ready true false false)))))

(deftest readiness-state-merge-ready-behind
  (testing "merge-ready + CI passed + behind → behind-main"
    (is (= [:behind-main 0.85]
           (sut/readiness-state :merge-ready true false true)))))

(deftest readiness-state-merge-ready-ci-failing
  (testing "merge-ready + CI failing → ci-failing (regression: was :unknown)"
    (is (= [:ci-failing 0.5]
           (sut/readiness-state :merge-ready false true false)))))

(deftest readiness-state-merge-ready-ci-pending
  (testing "merge-ready + CI pending → needs-review (regression: was :unknown)"
    (is (= [:needs-review 0.7]
           (sut/readiness-state :merge-ready false false false)))))

;; ============================================================================
;; readiness-blockers-summary
;; ============================================================================

(deftest blockers-summary-ready
  (testing "No blockers → ready"
    (is (= "ready"
           (sut/readiness-blockers-summary {:readiness/blockers []})))))

(deftest blockers-summary-nil-blockers
  (testing "Nil blockers → ready"
    (is (= "ready"
           (sut/readiness-blockers-summary {})))))

(deftest blockers-summary-review
  (testing "Review blocker → review"
    (is (= "review"
           (sut/readiness-blockers-summary
             {:readiness/blockers [{:blocker/type :review :blocker/message "Needs review"}]})))))

(deftest blockers-summary-ci
  (testing "CI blocker → CI"
    (is (= "CI"
           (sut/readiness-blockers-summary
             {:readiness/blockers [{:blocker/type :ci :blocker/message "CI failing"}]})))))

(deftest blockers-summary-multiple
  (testing "Multiple blockers joined with comma"
    (let [summary (sut/readiness-blockers-summary
                    {:readiness/blockers [{:blocker/type :ci :blocker/message "CI failing"}
                                          {:blocker/type :review :blocker/message "Needs review"}
                                          {:blocker/type :behind-main :blocker/message "Behind"}]})]
      (is (.contains summary "CI"))
      (is (.contains summary "review"))
      (is (.contains summary "rebase")))))

(deftest blockers-summary-draft
  (testing "Draft blocker → draft"
    (is (= "draft"
           (sut/readiness-blockers-summary
             {:readiness/blockers [{:blocker/type :draft :blocker/message "Draft PR"}]})))))

;; ============================================================================
;; derive-risk — scoring changes
;; ============================================================================

(deftest derive-risk-ci-fail-is-high
  (testing "CI failing → high risk"
    (let [risk (sut/derive-risk {:pr/status :open :pr/ci-status :failed
                                 :pr/additions 100 :pr/deletions 50})]
      (is (= :high (:risk/level risk))))))

(deftest derive-risk-ci-fail-plus-changes-requested-is-critical
  (testing "CI failing + changes requested → critical"
    (let [risk (sut/derive-risk {:pr/status :changes-requested :pr/ci-status :failed
                                 :pr/additions 100 :pr/deletions 50})]
      (is (= :critical (:risk/level risk))))))

(deftest derive-risk-large-pr-is-medium
  (testing ">500 LOC → medium risk"
    (let [risk (sut/derive-risk {:pr/status :open :pr/ci-status :passed
                                 :pr/additions 400 :pr/deletions 200})]
      (is (= :medium (:risk/level risk))))))

(deftest derive-risk-small-pr-is-low
  (testing "<200 LOC → low risk"
    (let [risk (sut/derive-risk {:pr/status :open :pr/ci-status :passed
                                 :pr/additions 50 :pr/deletions 20})]
      (is (= :low (:risk/level risk))))))

(deftest derive-risk-uses-max-not-average
  (testing "Max-of-factors scoring: one high signal isn't diluted"
    (let [risk (sut/derive-risk {:pr/status :open :pr/ci-status :passed
                                 :pr/additions 600 :pr/deletions 100
                                 :pr/changed-files-count 2})]
      ;; 700 LOC = score 0.75, 2 files = score 0.2
      ;; Max = 0.75 (not average 0.475), so level = :medium
      (is (= :medium (:risk/level risk)))
      (is (>= (:risk/score risk) 0.7)))))

;; ============================================================================
;; project-pr-row — size column color coding
;; ============================================================================

(defn- pr-row-with-size
  "Helper: build a minimal PR with given additions/deletions and call project-pr-row."
  [adds dels]
  (sut/project-pr-row {:pr/repo "test/repo" :pr/number 1 :pr/title "t"
                        :pr/status :open :pr/ci-status :passed
                        :pr/additions adds :pr/deletions dels}
                      {}))

(deftest size-color-red-over-1000
  (testing "Total > red threshold (1000) → status-fail color"
    (let [row (pr-row-with-size 800 300)]
      (is (= [220 50 40] (:ready-fg row))))))

(deftest size-color-yellow-over-500
  (testing "Total > yellow threshold (500) but ≤ red → status-warning color"
    (let [row (pr-row-with-size 400 200)]
      (is (= [200 160 0] (:ready-fg row))))))

(deftest size-color-neutral-over-200
  (testing "Total > green threshold (200) but ≤ yellow → no color (nil)"
    (let [row (pr-row-with-size 150 100)]
      (is (nil? (:ready-fg row))))))

(deftest size-color-green-under-200
  (testing "Total ≤ green threshold (200) → status-pass color"
    (let [row (pr-row-with-size 50 20)]
      (is (= [0 180 80] (:ready-fg row))))))

(deftest size-color-zero-shows-dash
  (testing "Zero additions and deletions → dash display, green color"
    (let [row (pr-row-with-size 0 0)]
      (is (= "—" (:ready row)))
      (is (= [0 180 80] (:ready-fg row))))))

;; ============================================================================
;; clean-agent-content
;; ============================================================================

(deftest clean-agent-content-unescapes-newlines
  (testing "literal \\n becomes real newline, splitting into multiple lines"
    (let [lines (trees/clean-agent-content "line one\\nline two\\nline three")]
      (is (= ["line one" "line two" "line three"] (vec lines))))))

(deftest clean-agent-content-filters-sse-events
  (testing "JSON SSE event lines are removed"
    (let [content "Review complete\n{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":100}}\nDone"
          lines (trees/clean-agent-content content)]
      (is (= ["Review complete" "Done"] (vec lines))))))

(deftest clean-agent-content-strips-markdown-links
  (testing "markdown [text](url) links become plain text"
    (let [lines (trees/clean-agent-content "See [foo.clj](/path/to/foo.clj#L42) for details")]
      (is (= ["See foo.clj for details"] (vec lines))))))

(deftest clean-agent-content-nil-safe
  (testing "nil content returns a sequence with one empty string"
    (is (= [""] (vec (trees/clean-agent-content nil))))))

(deftest clean-agent-content-combined
  (testing "unescapes, filters SSE, and strips links together"
    (let [content "Issue 1: missing test\\n{\"type\":\"turn.completed\"}\nSee [foo.clj](/foo.clj) for context"
          lines (trees/clean-agent-content content)]
      (is (= ["Issue 1: missing test" "See foo.clj for context"] (vec lines))))))

;; ============================================================================
;; temporal-bucket — regression for wrong some-> / .between call order
;; (Bug: some-> threaded wf-date as receiver of .between, but LocalDate has no
;;  2-arg between method. Correct call: ChronoUnit/DAYS.between(start, end).)
;; ============================================================================

(defn- date-days-ago
  "Return a java.util.Date for N days before today (midnight local time)."
  [n]
  (-> (LocalDate/now)
      (.minusDays n)
      (.atStartOfDay (ZoneId/systemDefault))
      .toInstant
      Date/from))

(deftest temporal-bucket-today
  (testing "workflow started today → :today"
    (is (= :today
           (helpers/temporal-bucket {:started-at (date-days-ago 0)}
                                    (LocalDate/now))))))

(deftest temporal-bucket-yesterday
  (testing "workflow started 1 day ago → :yesterday"
    (is (= :yesterday
           (helpers/temporal-bucket {:started-at (date-days-ago 1)}
                                    (LocalDate/now))))))

(deftest temporal-bucket-this-week
  (testing "workflow started 3 days ago → :this-week"
    (is (= :this-week
           (helpers/temporal-bucket {:started-at (date-days-ago 3)}
                                    (LocalDate/now))))))

(deftest temporal-bucket-this-month
  (testing "workflow started 15 days ago → :this-month"
    (is (= :this-month
           (helpers/temporal-bucket {:started-at (date-days-ago 15)}
                                    (LocalDate/now))))))

(deftest temporal-bucket-older
  (testing "workflow started 60 days ago → :older"
    (is (= :older
           (helpers/temporal-bucket {:started-at (date-days-ago 60)}
                                    (LocalDate/now))))))

(deftest temporal-bucket-nil-started-at
  (testing "nil started-at → :unknown (no exception)"
    (is (= :unknown
           (helpers/temporal-bucket {:started-at nil} (LocalDate/now))))))

(deftest temporal-bucket-missing-started-at
  (testing "missing started-at key → :unknown (no exception)"
    (is (= :unknown
           (helpers/temporal-bucket {} (LocalDate/now))))))

(deftest group-workflows-with-headers-dated
  (testing "dated workflows produce bucket headers without throwing"
    (let [wfs [{:id "wf1" :name "Today wf"  :status :running :started-at (date-days-ago 0)}
               {:id "wf2" :name "Old wf"    :status :success :started-at (date-days-ago 40)}]
          [rows mapped-idx] (helpers/group-workflows-with-headers wfs 0)]
      (is (some :_header? rows)   "Expected at least one header row")
      (is (= 2 (count (remove :_header? rows))) "Both workflows appear as non-header rows")
      (is (number? mapped-idx)    "mapped-idx is a number"))))
