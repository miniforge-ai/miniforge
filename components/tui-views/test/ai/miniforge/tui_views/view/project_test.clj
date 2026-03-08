(ns ai.miniforge.tui-views.view.project-test
  "Unit tests for extracted helper functions in project.clj."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.tui-views.view.project :as sut]))

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
      (is (= :green (:fg (first nodes))))
      (is (.contains (:label (first nodes)) "passed")))))

(deftest ci-section-nodes-failed
  (testing "Failed CI produces red header"
    (let [nodes (sut/ci-section-nodes :failed [])]
      (is (= :red (:fg (first nodes)))))))

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
      (is (= :red (:fg node))))))

(deftest behind-main-node-not-behind
  (testing "Not behind → green node"
    (let [node (sut/behind-main-node false nil)]
      (is (.contains (:label node) "no"))
      (is (= :green (:fg node))))))

;; ============================================================================
;; review-node
;; ============================================================================

(deftest review-node-approved
  (testing "Approved → green node"
    (let [node (sut/review-node :approved)]
      (is (.contains (:label node) "approved"))
      (is (= :green (:fg node))))))

(deftest review-node-changes-requested
  (testing "Changes requested → red node"
    (let [node (sut/review-node :changes-requested)]
      (is (.contains (:label node) "changes requested"))
      (is (= :red (:fg node))))))

(deftest review-node-reviewing
  (testing "Reviewing → yellow node"
    (let [node (sut/review-node :reviewing)]
      (is (.contains (:label node) "review required"))
      (is (= :yellow (:fg node))))))

(deftest review-node-draft
  (testing "Draft → no color"
    (let [node (sut/review-node :draft)]
      (is (.contains (:label node) "draft"))
      (is (nil? (:fg node))))))

(deftest review-node-default
  (testing "Unknown status → pending with yellow"
    (let [node (sut/review-node :unknown)]
      (is (.contains (:label node) "pending"))
      (is (= :yellow (:fg node))))))

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
      (is (= :green (:fg (first nodes))))
      (is (.contains (:label (first nodes)) "2/2 passed")))))

(deftest gates-section-nodes-some-failed
  (testing "Some gates failed → yellow header + mixed children"
    (let [gates [{:gate/id :lint :gate/passed? true}
                 {:gate/id :test :gate/passed? false}]
          nodes (sut/gates-section-nodes gates)]
      (is (= 3 (count nodes)))
      (is (= :yellow (:fg (first nodes))))
      (is (.contains (:label (first nodes)) "1/2 passed"))
      (is (= :green (:fg (second nodes))))
      (is (= :red (:fg (nth nodes 2)))))))

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
      (is (= :red (:fg (second nodes))))
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
