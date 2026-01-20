(ns ai.miniforge.reporting.views-test
  "Tests for reporting view renderers."
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.string :as str]
   [ai.miniforge.reporting.views :as views]))

;------------------------------------------------------------------------------ Layer 0
;; Test data

(def sample-system-status
  {:workflows {:active 2 :pending 1 :completed 10 :failed 1}
   :resources {:tokens-used 5000 :cost-usd 0.25}
   :meta-loop {:status :active :pending-improvements 3}
   :alerts [{:type :failed-workflows
             :severity :error
             :message "1 failed workflow(s)"}]})

(def sample-workflows
  [{:workflow/id #uuid "12345678-1234-1234-1234-123456789012"
    :workflow/status :running
    :workflow/phase :implement
    :workflow/created-at 1234567890000}
   {:workflow/id #uuid "87654321-4321-4321-4321-210987654321"
    :workflow/status :completed
    :workflow/phase :done
    :workflow/created-at 1234567890000}])

(def sample-workflow-detail
  {:header {:id #uuid "12345678-1234-1234-1234-123456789012"
            :status :running
            :phase :implement
            :created-at 1234567890000
            :updated-at 1234567890000}
   :timeline [{:phase :spec :status :completed :started-at 100 :completed-at 200}
              {:phase :plan :status :completed :started-at 200 :completed-at 300}
              {:phase :implement :status :running :started-at 300 :completed-at nil}]
   :current-task {:description "Implement feature"
                  :agent :implementer
                  :status :running}
   :artifacts [{:id #uuid "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
                :type :code
                :created-at 1234567890000}]
   :logs []})

(def sample-meta-loop
  {:signals [{:signal/id #uuid "11111111-1111-1111-1111-111111111111"
              :signal/type :workflow-failed
              :signal/timestamp 1234567890000}]
   :pending-improvements [{:improvement/id #uuid "22222222-2222-2222-2222-222222222222"
                           :improvement/type :rule-addition
                           :improvement/confidence 0.85
                           :improvement/rationale "Add validation rule"}]
   :recent-improvements []})

;------------------------------------------------------------------------------ Layer 1
;; ANSI helper tests

(deftest test-ansi
  (testing "ansi wraps text with color codes"
    (let [colored (views/ansi :green "test")]
      (is (str/includes? colored "test"))
      (is (str/includes? colored "\033["))))
  
  (testing "status-color returns appropriate color"
    (is (= :green (views/status-color :completed)))
    (is (= :yellow (views/status-color :pending)))
    (is (= :red (views/status-color :failed)))))

;------------------------------------------------------------------------------ Layer 2
;; Box drawing tests

(deftest test-draw-box
  (testing "draw-box creates box around content"
    (let [box (views/draw-box "Title" "Content line 1\nContent line 2" 40)]
      (is (str/includes? box "Title"))
      (is (str/includes? box "Content line 1"))
      (is (str/includes? box "Content line 2"))
      (is (str/includes? box "┌"))
      (is (str/includes? box "└"))
      (is (str/includes? box "│")))))

(deftest test-draw-separator
  (testing "draw-separator creates horizontal line"
    (let [sep (views/draw-separator 20)]
      (is (= 20 (count sep)))
      (is (every? #(= % \─) sep)))))

;------------------------------------------------------------------------------ Layer 3
;; Table formatting tests

(deftest test-format-table
  (testing "format-table creates aligned table"
    (let [headers ["Col1" "Col2" "Col3"]
          rows [["A" "B" "C"]
                ["Long" "Longer" "Longest"]]
          table (views/format-table headers rows)]
      
      (is (str/includes? table "Col1"))
      (is (str/includes? table "Col2"))
      (is (str/includes? table "Col3"))
      (is (str/includes? table "A"))
      (is (str/includes? table "Longest"))
      (is (str/includes? table "─")))))

;------------------------------------------------------------------------------ Layer 4
;; System overview renderer tests

(deftest test-render-system-overview
  (testing "render-system-overview creates formatted output"
    (let [output (views/render-system-overview sample-system-status)]
      
      (is (str/includes? output "WORKFLOWS"))
      (is (str/includes? output "RESOURCES"))
      (is (str/includes? output "META-LOOP"))
      (is (str/includes? output "ALERTS"))
      
      (is (str/includes? output "Active:"))
      (is (str/includes? output "2"))
      (is (str/includes? output "Tokens Used:"))
      (is (str/includes? output "5000"))
      (is (str/includes? output "$0.2500")))))

(deftest test-render-system-overview-no-alerts
  (testing "render-system-overview handles empty alerts"
    (let [status (assoc sample-system-status :alerts [])
          output (views/render-system-overview status)]
      
      (is (str/includes? output "No alerts")))))

;------------------------------------------------------------------------------ Layer 5
;; Workflow list renderer tests

(deftest test-render-workflow-list
  (testing "render-workflow-list creates table"
    (let [output (views/render-workflow-list sample-workflows)]
      
      (is (str/includes? output "ID"))
      (is (str/includes? output "Status"))
      (is (str/includes? output "Phase"))
      (is (str/includes? output "Created"))
      
      (is (str/includes? output "12345678"))
      (is (str/includes? output "running"))
      (is (str/includes? output "implement")))))

(deftest test-render-workflow-list-empty
  (testing "render-workflow-list handles empty list"
    (let [output (views/render-workflow-list [])]
      
      (is (str/includes? output "No workflows found")))))

;------------------------------------------------------------------------------ Layer 6
;; Workflow detail renderer tests

(deftest test-render-workflow-detail
  (testing "render-workflow-detail creates detailed view"
    (let [output (views/render-workflow-detail sample-workflow-detail)]
      
      (is (str/includes? output "WORKFLOW HEADER"))
      (is (str/includes? output "TIMELINE"))
      (is (str/includes? output "CURRENT TASK"))
      (is (str/includes? output "ARTIFACTS"))
      
      (is (str/includes? output "12345678-1234-1234-1234-123456789012"))
      (is (str/includes? output "running"))
      (is (str/includes? output "implement"))
      (is (str/includes? output "Implement feature")))))

(deftest test-render-workflow-detail-not-found
  (testing "render-workflow-detail handles nil"
    (let [output (views/render-workflow-detail nil)]
      
      (is (str/includes? output "Workflow not found")))))

;------------------------------------------------------------------------------ Layer 7
;; Meta-loop renderer tests

(deftest test-render-meta-loop
  (testing "render-meta-loop creates dashboard"
    (let [output (views/render-meta-loop sample-meta-loop)]
      
      (is (str/includes? output "RECENT SIGNALS"))
      (is (str/includes? output "PENDING IMPROVEMENTS"))
      (is (str/includes? output "RECENT IMPROVEMENTS"))
      
      (is (str/includes? output "workflow-failed"))
      (is (str/includes? output "rule-addition"))
      (is (str/includes? output "0.85")))))

(deftest test-render-meta-loop-empty
  (testing "render-meta-loop handles empty data"
    (let [status {:signals []
                  :pending-improvements []
                  :recent-improvements []}
          output (views/render-meta-loop status)]
      
      (is (str/includes? output "No recent signals"))
      (is (str/includes? output "No pending improvements"))
      (is (str/includes? output "No recent improvements")))))

;------------------------------------------------------------------------------ Layer 8
;; EDN renderer tests

(deftest test-render-edn
  (testing "render-edn outputs valid EDN"
    (let [data {:foo "bar" :baz 42}
          output (views/render-edn data)]
      
      (is (str/includes? output ":foo"))
      (is (str/includes? output "bar"))
      (is (str/includes? output ":baz"))
      (is (str/includes? output "42"))
      
      ;; Verify it's parseable EDN
      (is (map? (read-string output))))))
