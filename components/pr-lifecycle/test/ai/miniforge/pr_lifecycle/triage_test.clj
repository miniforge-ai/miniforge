(ns ai.miniforge.pr-lifecycle.triage-test
  "Unit tests for comment triage and CI failure parsing.

   Tests comment classification, batch triage, CI log parsing,
   file extraction, and review change grouping."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.pr-lifecycle.triage :as triage]))

;------------------------------------------------------------------------------ Comment Parsing

(deftest parse-comment-string-test
  (testing "parse-comment from plain string"
    (let [parsed (triage/parse-comment "Fix the bug")]
      (is (= "Fix the bug" (:comment/body parsed)))
      (is (nil? (:comment/author parsed)))
      (is (nil? (:comment/path parsed)))
      (is (nil? (:comment/line parsed))))))

(deftest parse-comment-map-test
  (testing "parse-comment from structured map"
    (let [parsed (triage/parse-comment {:body "Fix this"
                                         :author "alice"
                                         :path "src/foo.clj"
                                         :line 42})]
      (is (= "Fix this" (:comment/body parsed)))
      (is (= "alice" (:comment/author parsed)))
      (is (= "src/foo.clj" (:comment/path parsed)))
      (is (= 42 (:comment/line parsed))))))

;------------------------------------------------------------------------------ Comment Classification

(deftest classify-actionable-comment-test
  (testing "Comment with fix request is actionable"
    (let [result (triage/classify-comment "Please fix the null pointer exception")]
      (is (:actionable? result))
      (is (= :actionable-indicators (:reason result)))
      (is (pos? (get-in result [:scores :actionable])))))

  (testing "Comment with bug report is actionable"
    (let [result (triage/classify-comment "Bug: this crashes when input is nil")]
      (is (:actionable? result))))

  (testing "Comment about security is actionable"
    (let [result (triage/classify-comment "This is a security vulnerability - SQL injection possible")]
      (is (:actionable? result))
      (is (pos? (get-in result [:scores :actionable]))))))

(deftest classify-non-actionable-comment-test
  (testing "LGTM comment is non-actionable"
    (let [result (triage/classify-comment "LGTM, looks good!")]
      (is (not (:actionable? result)))
      (is (= :non-actionable-indicators (:reason result)))))

  (testing "Approval comment is non-actionable"
    (let [result (triage/classify-comment "Approved, ship it!")]
      (is (not (:actionable? result)))))

  (testing "Nitpick comment is non-actionable"
    (let [result (triage/classify-comment "nit: maybe rename this variable")]
      (is (not (:actionable? result))))))

(deftest classify-empty-comment-test
  (testing "Empty comment is non-actionable"
    (let [result (triage/classify-comment "")]
      (is (not (:actionable? result)))
      (is (= :no-indicators (:reason result)))))

  (testing "Nil body in map is non-actionable"
    (let [result (triage/classify-comment {:body nil})]
      (is (not (:actionable? result))))))

(deftest classify-whitelisted-author-test
  (testing "Whitelisted author always actionable"
    (let [result (triage/classify-comment
                   {:body "Just a note" :author "boss"}
                   :author-whitelist #{"boss"})]
      (is (:actionable? result))
      (is (= :whitelisted-author (:reason result))))))

(deftest classify-threshold-test
  (testing "Higher threshold requires more indicators"
    (let [result-low (triage/classify-comment "please check" :threshold 1)
          result-high (triage/classify-comment "please check" :threshold 5)]
      (is (:actionable? result-low)
          "Low threshold should match")
      (is (not (:actionable? result-high))
          "High threshold should not match"))))

(deftest classify-mixed-signals-test
  (testing "Actionable wins when score exceeds non-actionable"
    (let [result (triage/classify-comment
                   "Please fix this bug, it's a security issue")]
      (is (:actionable? result))
      (is (> (get-in result [:scores :actionable])
             (get-in result [:scores :non-actionable])))))

  (testing "Non-actionable wins when it has higher score"
    (let [result (triage/classify-comment "Looks good, LGTM, nice work!")]
      (is (not (:actionable? result))))))

(deftest classify-indicators-returned-test
  (testing "Matching indicators are returned for inspection"
    (let [result (triage/classify-comment "Please fix the error")]
      (is (seq (get-in result [:indicators :actionable])))
      (is (some #{"please"} (get-in result [:indicators :actionable])))
      (is (some #{"fix"} (get-in result [:indicators :actionable]))))))

;------------------------------------------------------------------------------ Batch Triage

(deftest triage-comments-default-policy-test
  (testing "Default policy returns only actionable comments"
    (let [comments ["Please fix the bug"
                    "LGTM"
                    "Great work!"
                    "This is a security vulnerability"]
          result (triage/triage-comments comments)]
      (is (= 2 (get-in result [:stats :actionable-count])))
      (is (= 2 (get-in result [:stats :non-actionable-count])))
      (is (= 4 (get-in result [:stats :total])))
      (is (= :actionable-only (get-in result [:stats :policy])))
      (is (every? :actionable? (:actionable result))))))

(deftest triage-comments-all-policy-test
  (testing ":all policy returns all classified comments"
    (let [comments ["Fix this" "LGTM"]
          result (triage/triage-comments comments :policy :all)]
      (is (= 2 (count (:actionable result)))
          "All comments should appear in :actionable when policy is :all"))))

(deftest triage-comments-none-policy-test
  (testing ":none policy returns empty actionable"
    (let [comments ["Fix this" "Security bug here"]
          result (triage/triage-comments comments :policy :none)]
      (is (empty? (:actionable result))))))

(deftest triage-empty-comments-test
  (testing "Triaging empty sequence returns zero stats"
    (let [result (triage/triage-comments [])]
      (is (= 0 (get-in result [:stats :total])))
      (is (empty? (:actionable result)))
      (is (empty? (:non-actionable result))))))

;------------------------------------------------------------------------------ CI Failure Parsing

(deftest parse-ci-failure-test-failures-test
  (testing "Parses test failure lines from logs"
    (let [logs "FAIL in (test-foo)
expected: 1
actual: 2
ERROR in (test-bar)
Something else"
          result (triage/parse-ci-failure logs)]
      (is (pos? (count (:test-failures result))))
      (is (some #(clojure.string/includes? % "FAIL") (:test-failures result)))
      (is (some #(clojure.string/includes? % "ERROR") (:test-failures result))))))

(deftest parse-ci-failure-lint-errors-test
  (testing "Parses lint error lines from logs"
    (let [logs "src/foo.clj:10:5: error: undefined symbol
src/bar.clj:20:1: warning: unused import"
          result (triage/parse-ci-failure logs)]
      (is (= 2 (count (:lint-errors result))))
      (is (some #(clojure.string/includes? % "error:") (:lint-errors result)))
      (is (some #(clojure.string/includes? % "warning:") (:lint-errors result))))))

(deftest parse-ci-failure-build-errors-test
  (testing "Parses build error lines from logs"
    (let [logs "Compiling stuff...
BUILD FAILED
Could not resolve dependency foo/bar"
          result (triage/parse-ci-failure logs)]
      (is (= 2 (count (:build-errors result))))
      (is (some #(clojure.string/includes? % "BUILD FAILED") (:build-errors result))))))

(deftest parse-ci-failure-summary-test
  (testing "Generates actionable summary from parsed results"
    (let [logs "FAIL in (test-foo)
BUILD FAILED"
          result (triage/parse-ci-failure logs)]
      (is (string? (:actionable-summary result)))
      (is (clojure.string/includes? (:actionable-summary result) "test failure"))
      (is (clojure.string/includes? (:actionable-summary result) "build error")))))

(deftest parse-ci-failure-empty-logs-test
  (testing "Empty logs produce empty results"
    (let [result (triage/parse-ci-failure "")]
      (is (empty? (:test-failures result)))
      (is (empty? (:lint-errors result)))
      (is (empty? (:build-errors result))))))

(deftest parse-ci-failure-lines-input-test
  (testing "Accepts pre-split lines as input"
    (let [result (triage/parse-ci-failure ["FAIL in (test-foo)" "All good"])]
      (is (= 1 (count (:test-failures result)))))))

;------------------------------------------------------------------------------ File Extraction

(deftest extract-failing-files-test
  (testing "Extracts file paths from CI failure data"
    (let [failure {:test-failures ["at foo_test.clj:42"
                                   "in src/bar.clj line 10"]
                   :lint-errors ["src/baz.clj:10:5: error: undefined"]
                   :build-errors []}
          files (triage/extract-failing-files failure)]
      (is (set? files))
      (is (contains? files "foo_test.clj"))
      (is (contains? files "src/baz.clj")))))

(deftest extract-failing-files-filters-java-test
  (testing "Filters out java.* paths"
    (let [failure {:test-failures ["at java.lang.Thread:123"]
                   :lint-errors []
                   :build-errors []}
          files (triage/extract-failing-files failure)]
      (is (not (some #(clojure.string/starts-with? % "java.") files))))))

(deftest extract-failing-files-empty-test
  (testing "Empty failure data returns empty set"
    (let [failure {:test-failures [] :lint-errors [] :build-errors []}]
      (is (empty? (triage/extract-failing-files failure))))))

;------------------------------------------------------------------------------ Review Change Extraction

(deftest extract-requested-changes-test
  (testing "Extracts file-scoped changes from actionable comments"
    (let [comments [{:comment {:comment/body "Fix the null check"
                               :comment/path "src/foo.clj"
                               :comment/line 42
                               :comment/author "reviewer"}}
                    {:comment {:comment/body "General note"
                               :comment/path nil
                               :comment/line nil
                               :comment/author "reviewer"}}]
          changes (triage/extract-requested-changes comments)]
      (is (= 1 (count changes))
          "Only comments with file context should be extracted")
      (is (= "src/foo.clj" (:file (first changes))))
      (is (= 42 (:line (first changes)))))))

(deftest group-changes-by-file-test
  (testing "Groups changes by file path"
    (let [changes [{:file "src/a.clj" :line 10 :change "Fix A"}
                   {:file "src/a.clj" :line 20 :change "Fix A2"}
                   {:file "src/b.clj" :line 5 :change "Fix B"}]
          grouped (triage/group-changes-by-file changes)]
      (is (= 2 (count grouped))
          "Should have 2 file groups")
      (let [a-group (first (filter #(= "src/a.clj" (:file %)) grouped))
            b-group (first (filter #(= "src/b.clj" (:file %)) grouped))]
        (is (= 2 (count (:changes a-group))))
        (is (= #{10 20} (:lines a-group)))
        (is (= 1 (count (:changes b-group))))))))

(deftest group-changes-empty-test
  (testing "Empty changes produce empty groups"
    (is (empty? (triage/group-changes-by-file [])))))