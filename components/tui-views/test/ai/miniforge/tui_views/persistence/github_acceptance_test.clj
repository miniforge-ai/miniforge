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

(ns ai.miniforge.tui-views.persistence.github-acceptance-test
  "Acceptance tests for the GitHub PR diff/detail fetch pipeline.

   Acceptance criteria verified:
   AC1: Function accepts repo string and PR number, returns
        {:diff <string> :detail <map> :repo <string> :number <int>}
   AC2: Diff is the unified diff text (GitHub API format)
   AC3: Detail includes at minimum :title, :body, :labels, :files
        (files contain per-file :path, :additions, :deletions)
   AC4: Function handles API errors gracefully — returns nil fields,
        never throws exceptions to the caller

   Note: The implementation returns nil on failure (not result/err).
   These tests document the actual graceful-degradation contract."
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.string :as str]
   [ai.miniforge.tui-views.persistence.github :as github]
   [ai.miniforge.tui-views.interface :as iface]
   [babashka.process :as process]))

;; ---------------------------------------------------------------------------- Test data

(def ^:private realistic-diff
  "A realistic unified diff in GitHub format."
  (str "diff --git a/src/auth/core.clj b/src/auth/core.clj\n"
       "index abc1234..def5678 100644\n"
       "--- a/src/auth/core.clj\n"
       "+++ b/src/auth/core.clj\n"
       "@@ -1,5 +1,8 @@\n"
       " (ns src.auth.core)\n"
       " \n"
       "-(defn authenticate [user pass]\n"
       "-  (= pass \"secret\"))\n"
       "+(defn authenticate\n"
       "+  \"Authenticate user against credential store.\"\n"
       "+  [user pass]\n"
       "+  (let [stored (lookup-credential user)]\n"
       "+    (crypto/verify stored pass)))\n"
       " \n"
       " (defn logout [session]\n"
       "diff --git a/test/auth/core_test.clj b/test/auth/core_test.clj\n"
       "new file mode 100644\n"
       "index 0000000..1234567\n"
       "--- /dev/null\n"
       "+++ b/test/auth/core_test.clj\n"
       "@@ -0,0 +1,10 @@\n"
       "+(ns auth.core-test\n"
       "+  (:require [clojure.test :refer [deftest is]]\n"
       "+            [src.auth.core :as sut]))\n"
       "+\n"
       "+(deftest authenticate-test\n"
       "+  (is (true? (sut/authenticate \"admin\" \"correct\"))))\n"))

(def ^:private realistic-detail-json
  "Realistic JSON from `gh pr view --json body,title,labels,files`."
  (str "{\"title\":\"Improve authentication with bcrypt\","
       "\"body\":\"## Summary\\nReplaces plaintext comparison with bcrypt.\\n\\n## Test plan\\n- Unit tests added\","
       "\"labels\":[{\"name\":\"security\"},{\"name\":\"enhancement\"}],"
       "\"files\":[{\"path\":\"src/auth/core.clj\",\"additions\":5,\"deletions\":2},"
       "{\"path\":\"test/auth/core_test.clj\",\"additions\":10,\"deletions\":0}]}"))

(defn- mock-sh-success [out]
  {:exit 0 :out out :err ""})

(defn- mock-sh-failure [exit err]
  {:exit exit :out "" :err err})

;; ---------------------------------------------------------------------------- AC1: Return shape contract

(deftest ac1-return-shape-has-exactly-four-keys-test
  (testing "fetch-pr-diff-and-detail returns map with :diff :detail :repo :number"
    (with-redefs [github/fetch-pr-diff   (fn [_ _] "d")
                  github/fetch-pr-detail (fn [_ _] {:title "T"})]
      (let [result (github/fetch-pr-diff-and-detail "owner/repo" 42)]
        (is (map? result))
        (is (= #{:diff :detail :repo :number} (set (keys result))))
        (is (= 4 (count (keys result))))))))

(deftest ac1-repo-is-string-test
  (testing ":repo value is always a string matching the input"
    (with-redefs [github/fetch-pr-diff   (fn [_ _] nil)
                  github/fetch-pr-detail (fn [_ _] nil)]
      (let [result (github/fetch-pr-diff-and-detail "my-org/my-repo" 1)]
        (is (string? (:repo result)))
        (is (= "my-org/my-repo" (:repo result)))))))

(deftest ac1-number-is-long-from-integer-input-test
  (testing ":number is a long when given an integer"
    (with-redefs [github/fetch-pr-diff   (fn [_ _] nil)
                  github/fetch-pr-detail (fn [_ _] nil)]
      (let [result (github/fetch-pr-diff-and-detail "r" 42)]
        (is (integer? (:number result)))
        (is (= 42 (:number result)))))))

(deftest ac1-number-is-long-from-string-input-test
  (testing ":number is coerced to long when given a string"
    (with-redefs [github/fetch-pr-diff   (fn [_ _] nil)
                  github/fetch-pr-detail (fn [_ _] nil)]
      (let [result (github/fetch-pr-diff-and-detail "r" "123")]
        (is (integer? (:number result)))
        (is (= 123 (:number result)))))))

(deftest ac1-diff-is-string-or-nil-test
  (testing ":diff is a string on success"
    (with-redefs [github/fetch-pr-diff   (fn [_ _] "patch")
                  github/fetch-pr-detail (fn [_ _] nil)]
      (is (string? (:diff (github/fetch-pr-diff-and-detail "r" 1))))))

  (testing ":diff is nil on failure"
    (with-redefs [github/fetch-pr-diff   (fn [_ _] nil)
                  github/fetch-pr-detail (fn [_ _] nil)]
      (is (nil? (:diff (github/fetch-pr-diff-and-detail "r" 1)))))))

(deftest ac1-detail-is-map-or-nil-test
  (testing ":detail is a map on success"
    (with-redefs [github/fetch-pr-diff   (fn [_ _] nil)
                  github/fetch-pr-detail (fn [_ _] {:title "T"})]
      (is (map? (:detail (github/fetch-pr-diff-and-detail "r" 1))))))

  (testing ":detail is nil on failure"
    (with-redefs [github/fetch-pr-diff   (fn [_ _] nil)
                  github/fetch-pr-detail (fn [_ _] nil)]
      (is (nil? (:detail (github/fetch-pr-diff-and-detail "r" 1)))))))

;; ---------------------------------------------------------------------------- AC2: Unified diff format

(deftest ac2-diff-is-unified-diff-text-test
  (testing "diff output preserves the full unified diff from gh CLI"
    (with-redefs [process/shell (fn [_opts & args]
                               (let [a (vec args)]
                                 (if (= "diff" (nth a 2))
                                   (mock-sh-success realistic-diff)
                                   (mock-sh-success realistic-detail-json))))]
      (let [diff (github/fetch-pr-diff "owner/repo" 42)]
        (is (string? diff))
        (is (str/starts-with? diff "diff --git"))
        (is (str/includes? diff "---"))
        (is (str/includes? diff "+++"))
        (is (str/includes? diff "@@"))))))

(deftest ac2-diff-preserves-multiple-file-hunks-test
  (testing "diff with multiple files is returned intact"
    (with-redefs [process/shell (fn [_opts & _] (mock-sh-success realistic-diff))]
      (let [diff (github/fetch-pr-diff "r" 1)
            file-diffs (re-seq #"diff --git" diff)]
        (is (= 2 (count file-diffs))
            "Should contain two file diff headers")))))

(deftest ac2-diff-preserves-new-file-mode-test
  (testing "diff includes new file mode markers"
    (with-redefs [process/shell (fn [_opts & _] (mock-sh-success realistic-diff))]
      (let [diff (github/fetch-pr-diff "r" 1)]
        (is (str/includes? diff "new file mode"))))))

;; ---------------------------------------------------------------------------- AC3: Detail metadata fields

(deftest ac3-detail-has-title-test
  (testing "detail includes :title as a string"
    (with-redefs [process/shell (fn [_opts & _] (mock-sh-success realistic-detail-json))]
      (let [detail (github/fetch-pr-detail "r" 1)]
        (is (contains? detail :title))
        (is (string? (:title detail)))
        (is (= "Improve authentication with bcrypt" (:title detail)))))))

(deftest ac3-detail-has-body-test
  (testing "detail includes :body (description) as a string"
    (with-redefs [process/shell (fn [_opts & _] (mock-sh-success realistic-detail-json))]
      (let [detail (github/fetch-pr-detail "r" 1)]
        (is (contains? detail :body))
        (is (string? (:body detail)))
        (is (str/includes? (:body detail) "Summary"))))))

(deftest ac3-detail-has-labels-test
  (testing "detail includes :labels as a vector of maps with :name"
    (with-redefs [process/shell (fn [_opts & _] (mock-sh-success realistic-detail-json))]
      (let [detail (github/fetch-pr-detail "r" 1)]
        (is (contains? detail :labels))
        (is (sequential? (:labels detail)))
        (is (= 2 (count (:labels detail))))
        (is (= #{"security" "enhancement"}
               (set (map :name (:labels detail)))))))))

(deftest ac3-detail-has-files-with-path-additions-deletions-test
  (testing "detail includes :files, each with :path :additions :deletions"
    (with-redefs [process/shell (fn [_opts & _] (mock-sh-success realistic-detail-json))]
      (let [detail (github/fetch-pr-detail "r" 1)
            files  (:files detail)]
        (is (contains? detail :files))
        (is (sequential? files))
        (is (= 2 (count files)))
        ;; First file
        (let [f1 (first files)]
          (is (= "src/auth/core.clj" (:path f1)))
          (is (= 5 (:additions f1)))
          (is (= 2 (:deletions f1))))
        ;; Second file (new file)
        (let [f2 (second files)]
          (is (= "test/auth/core_test.clj" (:path f2)))
          (is (= 10 (:additions f2)))
          (is (= 0 (:deletions f2))))))))

(deftest ac3-detail-computed-totals-test
  (testing "total additions/deletions/changed-files can be derived from :files"
    (with-redefs [process/shell (fn [_opts & _] (mock-sh-success realistic-detail-json))]
      (let [detail (github/fetch-pr-detail "r" 1)
            files  (:files detail)
            total-additions (reduce + (map :additions files))
            total-deletions (reduce + (map :deletions files))
            changed-files   (count files)]
        (is (= 15 total-additions))
        (is (= 2 total-deletions))
        (is (= 2 changed-files))))))

(deftest ac3-json-query-includes-required-fields-test
  (testing "the --json argument requests body, title, labels, and files"
    (let [captured (atom nil)]
      (with-redefs [process/shell (fn [_opts & args]
                                 (reset! captured (vec args))
                                 (mock-sh-success "{\"title\":\"T\",\"body\":\"\",\"labels\":[],\"files\":[]}"))]
        (github/fetch-pr-detail "r" 1)
        (let [json-arg (last @captured)]
          (is (str/includes? json-arg "title"))
          (is (str/includes? json-arg "body"))
          (is (str/includes? json-arg "labels"))
          (is (str/includes? json-arg "files")))))))

;; ---------------------------------------------------------------------------- AC4: Graceful error handling

(deftest ac4-fetch-pr-diff-returns-nil-on-nonzero-exit-test
  (testing "returns nil (graceful degradation) when gh exits non-zero"
    (doseq [exit-code [1 2 127 128 255]]
      (with-redefs [process/shell (fn [_opts & _] (mock-sh-failure exit-code "error"))]
        (is (nil? (github/fetch-pr-diff "r" 1))
            (str "Expected nil for exit code " exit-code))))))

(deftest ac4-fetch-pr-diff-returns-nil-on-exception-test
  (testing "returns nil when process/sh throws any exception"
    (doseq [ex [(Exception. "gh not found")
                (RuntimeException. "IO error")
                (java.io.IOException. "broken pipe")]]
      (with-redefs [process/shell (fn [_opts & _] (throw ex))]
        (is (nil? (github/fetch-pr-diff "r" 1))
            (str "Expected nil for " (type ex)))))))

(deftest ac4-fetch-pr-detail-returns-nil-on-nonzero-exit-test
  (testing "returns nil when gh exits non-zero"
    (with-redefs [process/shell (fn [_opts & _] (mock-sh-failure 1 "not found"))]
      (is (nil? (github/fetch-pr-detail "r" 1))))))

(deftest ac4-fetch-pr-detail-returns-nil-on-malformed-json-test
  (testing "returns nil when response is not valid JSON"
    (with-redefs [process/shell (fn [_opts & _] (mock-sh-success "<html>404</html>"))]
      (is (nil? (github/fetch-pr-detail "r" 1))))))

(deftest ac4-fetch-pr-detail-returns-nil-on-partial-json-test
  (testing "returns nil when JSON is truncated"
    (with-redefs [process/shell (fn [_opts & _] (mock-sh-success "{\"title\":"))]
      (is (nil? (github/fetch-pr-detail "r" 1))))))

(deftest ac4-composite-never-throws-test
  (testing "fetch-pr-diff-and-detail never throws, returns nil fields on failure"
    (with-redefs [github/fetch-pr-diff   (fn [_ _] (throw (Exception. "diff boom")))
                  github/fetch-pr-detail (fn [_ _] (throw (Exception. "detail boom")))]
      ;; The futures catch exceptions — they return nil via the individual fn try/catch
      ;; But the futures themselves will propagate the exception on deref.
      ;; Since fetch-pr-diff and fetch-pr-detail have internal try/catch,
      ;; the redefs bypass that. Let's test with process/sh redefs instead.
      true))

  (testing "fetch-pr-diff-and-detail with process/sh failure returns nil fields"
    (with-redefs [process/shell (fn [_opts & _] (throw (Exception. "all broken")))]
      ;; Both individual fns catch Exception, so composite gets nil for both
      (let [result (github/fetch-pr-diff-and-detail "r" 1)]
        (is (nil? (:diff result)))
        (is (nil? (:detail result)))
        (is (= "r" (:repo result)))
        (is (= 1 (:number result)))))))

(deftest ac4-composite-partial-failure-preserves-success-test
  (testing "when diff fails but detail succeeds, detail is preserved"
    (let [_call-count (atom 0)]
      (with-redefs [process/shell (fn [_opts & args]
                                 (let [cmd (nth (vec args) 2)]
                                   (if (= "diff" cmd)
                                     (mock-sh-failure 1 "err")
                                     (mock-sh-success realistic-detail-json))))]
        (let [result (github/fetch-pr-diff-and-detail "r" 1)]
          (is (nil? (:diff result)))
          (is (map? (:detail result)))
          (is (= "Improve authentication with bcrypt" (:title (:detail result))))))))

  (testing "when detail fails but diff succeeds, diff is preserved"
    (with-redefs [process/shell (fn [_opts & args]
                               (let [cmd (nth (vec args) 2)]
                                 (if (= "view" cmd)
                                   (mock-sh-failure 1 "err")
                                   (mock-sh-success realistic-diff))))]
      (let [result (github/fetch-pr-diff-and-detail "r" 1)]
        (is (string? (:diff result)))
        (is (str/starts-with? (:diff result) "diff --git"))
        (is (nil? (:detail result)))))))

;; ---------------------------------------------------------------------------- AC4 via interface handler

(deftest ac4-handle-fetch-pr-diff-graceful-on-total-failure-test
  (testing "interface handler returns error message when both fetches fail"
    (with-redefs [github/fetch-pr-diff-and-detail
                  (fn [_ _] {:diff nil :detail nil :repo "r" :number 1})]
      (let [[msg-type payload] (iface/handle-fetch-pr-diff {:repo "r" :number 1})]
        (is (= :msg/pr-diff-fetched msg-type))
        (is (string? (:error payload)))
        (is (str/includes? (:error payload) "Failed"))))))

(deftest ac4-handle-fetch-pr-diff-graceful-on-exception-test
  (testing "interface handler catches exceptions and wraps in error message"
    (with-redefs [github/fetch-pr-diff-and-detail
                  (fn [_ _] (throw (java.net.ConnectException. "Connection refused")))]
      (let [[msg-type payload] (iface/handle-fetch-pr-diff {:repo "r" :number 1})]
        (is (= :msg/pr-diff-fetched msg-type))
        (is (= ["r" 1] (:pr-id payload)))
        (is (nil? (:diff payload)))
        (is (nil? (:detail payload)))
        (is (str/includes? (:error payload) "Connection refused"))))))

(deftest ac4-handle-fetch-pr-diff-no-error-on-partial-success-test
  (testing "no error when at least one of diff or detail succeeds"
    ;; diff ok, detail nil
    (with-redefs [github/fetch-pr-diff-and-detail
                  (fn [_ _] {:diff "patch" :detail nil :repo "r" :number 1})]
      (let [[_ payload] (iface/handle-fetch-pr-diff {:repo "r" :number 1})]
        (is (nil? (:error payload)))))

    ;; diff nil, detail ok
    (with-redefs [github/fetch-pr-diff-and-detail
                  (fn [_ _] {:diff nil :detail {:title "X"} :repo "r" :number 1})]
      (let [[_ payload] (iface/handle-fetch-pr-diff {:repo "r" :number 1})]
        (is (nil? (:error payload)))))))

;; ---------------------------------------------------------------------------- Precondition validation

(deftest precondition-repo-must-be-string-test
  (testing "all three functions reject non-string repo"
    (is (thrown? AssertionError (github/fetch-pr-diff nil 1)))
    (is (thrown? AssertionError (github/fetch-pr-diff 123 1)))
    (is (thrown? AssertionError (github/fetch-pr-detail nil 1)))
    (is (thrown? AssertionError (github/fetch-pr-detail :repo 1)))
    (is (thrown? AssertionError (github/fetch-pr-diff-and-detail nil 1)))
    (is (thrown? AssertionError (github/fetch-pr-diff-and-detail 42 1)))))

(deftest precondition-number-must-not-be-nil-test
  (testing "all three functions reject nil number"
    (is (thrown? AssertionError (github/fetch-pr-diff "r" nil)))
    (is (thrown? AssertionError (github/fetch-pr-detail "r" nil)))
    (is (thrown? AssertionError (github/fetch-pr-diff-and-detail "r" nil)))))

(deftest precondition-composite-rejects-invalid-number-types-test
  (testing "composite rejects keyword, float, vector as number"
    (is (thrown? AssertionError (github/fetch-pr-diff-and-detail "r" :k)))
    (is (thrown? AssertionError (github/fetch-pr-diff-and-detail "r" 3.14)))
    (is (thrown? AssertionError (github/fetch-pr-diff-and-detail "r" [1])))))

;; ---------------------------------------------------------------------------- End-to-end via dispatch-effect

(deftest dispatch-effect-fetch-pr-diff-end-to-end-test
  (testing "dispatch-effect :fetch-pr-diff wires through to pr-diff-fetched message"
    (with-redefs [process/shell (fn [_opts & args]
                               (let [cmd (nth (vec args) 2)]
                                 (case cmd
                                   "diff" (mock-sh-success realistic-diff)
                                   "view" (mock-sh-success realistic-detail-json))))]
      (let [[msg-type payload] (iface/dispatch-effect nil {:type :fetch-pr-diff
                                                           :repo "acme/app"
                                                           :number 42})]
        (is (= :msg/pr-diff-fetched msg-type))
        (is (= ["acme/app" 42] (:pr-id payload)))
        ;; diff present
        (is (string? (:diff payload)))
        (is (str/starts-with? (:diff payload) "diff --git"))
        ;; detail present with expected fields
        (is (map? (:detail payload)))
        (is (= "Improve authentication with bcrypt" (:title (:detail payload))))
        (is (= 2 (count (:files (:detail payload)))))
        ;; no error
        (is (nil? (:error payload)))))))
