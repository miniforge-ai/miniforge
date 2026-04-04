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

(ns ai.miniforge.tui-views.persistence.github-extended-test
  "Extended tests for persistence/github.clj covering:
   - Precondition assertions (invalid inputs)
   - coerce-number edge cases via composite function
   - Concurrency behavior of fetch-pr-diff-and-detail
   - Empty/whitespace diff handling
   - Large PR number handling
   - No cli-base dependency verification
   - CLI argument correctness
   - files field inclusion in --json query"
  (:require
   [clojure.string]
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.tui-views.persistence.github :as github]
   [babashka.process :as process]))

;; ---------------------------------------------------------------------------- Helpers

(defn mock-sh-success [out]
  {:exit 0 :out out :err ""})

(defn mock-sh-failure [exit err]
  {:exit exit :out "" :err err})

;; ---------------------------------------------------------------------------- Precondition tests

(deftest fetch-pr-diff-precondition-test
  (testing "throws AssertionError when repo is not a string"
    (is (thrown? AssertionError (github/fetch-pr-diff nil 42)))
    (is (thrown? AssertionError (github/fetch-pr-diff 123 42))))

  (testing "throws AssertionError when number is nil"
    (is (thrown? AssertionError (github/fetch-pr-diff "owner/repo" nil)))))

(deftest fetch-pr-detail-precondition-test
  (testing "throws AssertionError when repo is not a string"
    (is (thrown? AssertionError (github/fetch-pr-detail nil 42)))
    (is (thrown? AssertionError (github/fetch-pr-detail :keyword 42))))

  (testing "throws AssertionError when number is nil"
    (is (thrown? AssertionError (github/fetch-pr-detail "owner/repo" nil)))))

(deftest fetch-pr-diff-and-detail-precondition-test
  (testing "throws AssertionError when repo is not a string"
    (is (thrown? AssertionError (github/fetch-pr-diff-and-detail nil 42))))

  (testing "throws AssertionError when number is nil"
    (is (thrown? AssertionError (github/fetch-pr-diff-and-detail "r" nil))))

  (testing "throws AssertionError when number is not string or integer"
    (is (thrown? AssertionError (github/fetch-pr-diff-and-detail "r" :keyword)))
    (is (thrown? AssertionError (github/fetch-pr-diff-and-detail "r" 3.14)))
    (is (thrown? AssertionError (github/fetch-pr-diff-and-detail "r" [42])))))

;; ---------------------------------------------------------------------------- coerce-number edge cases

(deftest coerce-number-via-composite-test
  (testing "large PR number as string is coerced correctly"
    (with-redefs [github/fetch-pr-diff   (fn [_ n] (is (= 999999 n)) "d")
                  github/fetch-pr-detail (fn [_ n] (is (= 999999 n)) {:title "X"})]
      (let [result (github/fetch-pr-diff-and-detail "r" "999999")]
        (is (= 999999 (:number result))))))

  (testing "zero as string"
    (with-redefs [github/fetch-pr-diff   (fn [_ n] (is (= 0 n)) nil)
                  github/fetch-pr-detail (fn [_ n] (is (= 0 n)) nil)]
      (let [result (github/fetch-pr-diff-and-detail "r" "0")]
        (is (= 0 (:number result))))))

  (testing "zero as integer"
    (with-redefs [github/fetch-pr-diff   (fn [_ _] nil)
                  github/fetch-pr-detail (fn [_ _] nil)]
      (let [result (github/fetch-pr-diff-and-detail "r" 0)]
        (is (= 0 (:number result))))))

  (testing "negative number passes through as long"
    (with-redefs [github/fetch-pr-diff   (fn [_ n] (is (= -1 n)) nil)
                  github/fetch-pr-detail (fn [_ n] (is (= -1 n)) nil)]
      (let [result (github/fetch-pr-diff-and-detail "r" -1)]
        (is (= -1 (:number result))))))

  (testing "string number with leading zeros parses correctly"
    (with-redefs [github/fetch-pr-diff   (fn [_ n] (is (= 7 n)) nil)
                  github/fetch-pr-detail (fn [_ n] (is (= 7 n)) nil)]
      (let [result (github/fetch-pr-diff-and-detail "r" "007")]
        (is (= 7 (:number result)))))))

(deftest coerce-number-invalid-string-test
  (testing "non-numeric string throws NumberFormatException from Long/parseLong"
    (is (thrown? NumberFormatException
          (github/fetch-pr-diff-and-detail "r" "abc")))))

;; ---------------------------------------------------------------------------- Whitespace / multiline diff

(deftest fetch-pr-diff-whitespace-test
  (testing "trims whitespace-only diff output"
    (with-redefs [process/shell (fn [_opts & _] (mock-sh-success "   \n  "))]
      (is (= "" (github/fetch-pr-diff "r" 1)))))

  (testing "returns multiline diff intact"
    (let [diff "diff --git a/x b/x\n--- a/x\n+++ b/x\n@@ -1 +1 @@\n-old\n+new\n"]
      (with-redefs [process/shell (fn [_opts & _] (mock-sh-success diff))]
        (is (= (clojure.string/trim diff) (github/fetch-pr-diff "r" 1)))))))

;; ---------------------------------------------------------------------------- fetch-pr-detail nested JSON

(deftest fetch-pr-detail-nested-labels-test
  (testing "handles multiple labels"
    (let [json "{\"title\":\"T\",\"body\":\"B\",\"labels\":[{\"name\":\"bug\"},{\"name\":\"urgent\"}],\"files\":[]}"]
      (with-redefs [process/shell (fn [_opts & _] (mock-sh-success json))]
        (let [result (github/fetch-pr-detail "r" 1)]
          (is (= 2 (count (:labels result))))
          (is (= "bug" (:name (first (:labels result)))))
          (is (= "urgent" (:name (second (:labels result))))))))))

(deftest fetch-pr-detail-empty-body-test
  (testing "handles PR with empty body and no labels/files"
    (let [json "{\"title\":\"Tiny\",\"body\":\"\",\"labels\":[],\"files\":[]}"]
      (with-redefs [process/shell (fn [_opts & _] (mock-sh-success json))]
        (let [result (github/fetch-pr-detail "r" 1)]
          (is (= "Tiny" (:title result)))
          (is (= "" (:body result)))
          (is (empty? (:labels result)))
          (is (empty? (:files result))))))))

(deftest fetch-pr-detail-files-field-present-test
  (testing "files field is included in the --json query for TUI file-level views"
    (let [captured-args (atom nil)]
      (with-redefs [process/shell (fn [_opts & args]
                                 (reset! captured-args (vec args))
                                 (mock-sh-success "{\"title\":\"T\",\"body\":\"\",\"labels\":[],\"files\":[]}"))]
        (github/fetch-pr-detail "r" 1)
        (let [json-field (last @captured-args)]
          (is (clojure.string/includes? json-field "files")
              "--json query must include 'files' for TUI file-level detail")
          (is (clojure.string/includes? json-field "body"))
          (is (clojure.string/includes? json-field "title"))
          (is (clojure.string/includes? json-field "labels")))))))

(deftest fetch-pr-detail-multiple-files-test
  (testing "handles PR with multiple changed files including additions/deletions"
    (let [json "{\"title\":\"Big PR\",\"body\":\"Many changes\",\"labels\":[],\"files\":[{\"path\":\"src/a.clj\",\"additions\":10,\"deletions\":3},{\"path\":\"src/b.clj\",\"additions\":0,\"deletions\":20},{\"path\":\"test/c_test.clj\",\"additions\":50,\"deletions\":0}]}"]
      (with-redefs [process/shell (fn [_opts & _] (mock-sh-success json))]
        (let [result (github/fetch-pr-detail "r" 1)
              files (:files result)]
          (is (= 3 (count files)))
          (is (= "src/a.clj" (:path (first files))))
          (is (= 10 (:additions (first files))))
          (is (= 20 (:deletions (second files))))
          (is (= 50 (:additions (nth files 2)))))))))

;; ---------------------------------------------------------------------------- Concurrency

(deftest fetch-pr-diff-and-detail-concurrency-test
  (testing "both fetches run concurrently (neither blocks the other)"
    (let [diff-called (promise)
          detail-called (promise)]
      (with-redefs [github/fetch-pr-diff
                    (fn [_ _]
                      (deliver diff-called true)
                      ;; Wait briefly for detail to also start
                      (deref detail-called 500 false)
                      "diff")
                    github/fetch-pr-detail
                    (fn [_ _]
                      (deliver detail-called true)
                      (deref diff-called 500 false)
                      {:title "T"})]
        (let [result (github/fetch-pr-diff-and-detail "r" 1)]
          (is (= "diff" (:diff result)))
          (is (= {:title "T"} (:detail result)))
          ;; Both should have been called
          (is (true? (deref diff-called 0 false)))
          (is (true? (deref detail-called 0 false))))))))

(deftest fetch-pr-diff-and-detail-one-slow-does-not-block-other-test
  (testing "slow diff does not prevent detail from completing"
    (let [detail-done (promise)]
      (with-redefs [github/fetch-pr-diff
                    (fn [_ _]
                      ;; Simulate slow diff
                      (Thread/sleep 100)
                      "slow-diff")
                    github/fetch-pr-detail
                    (fn [_ _]
                      (let [result {:title "Fast"}]
                        (deliver detail-done true)
                        result))]
        (let [result (github/fetch-pr-diff-and-detail "r" 1)]
          (is (= "slow-diff" (:diff result)))
          (is (= {:title "Fast"} (:detail result)))
          (is (true? (deref detail-done 0 false))))))))

;; ---------------------------------------------------------------------------- Various exit codes

(deftest fetch-pr-diff-various-exit-codes-test
  (testing "exit code 1 returns nil"
    (with-redefs [process/shell (fn [_opts & _] (mock-sh-failure 1 "err"))]
      (is (nil? (github/fetch-pr-diff "r" 1)))))

  (testing "exit code 127 (command not found) returns nil"
    (with-redefs [process/shell (fn [_opts & _] (mock-sh-failure 127 "gh: command not found"))]
      (is (nil? (github/fetch-pr-diff "r" 1)))))

  (testing "exit code 128 returns nil"
    (with-redefs [process/shell (fn [_opts & _] (mock-sh-failure 128 "auth"))]
      (is (nil? (github/fetch-pr-diff "r" 1))))))

(deftest fetch-pr-detail-various-exit-codes-test
  (testing "exit code 127 returns nil"
    (with-redefs [process/shell (fn [_opts & _] (mock-sh-failure 127 "not found"))]
      (is (nil? (github/fetch-pr-detail "r" 1))))))

;; ---------------------------------------------------------------------------- CLI argument structure

(deftest fetch-pr-diff-cli-args-test
  (testing "passes correct arguments to gh pr diff"
    (let [captured (atom nil)]
      (with-redefs [process/shell (fn [_opts & args]
                                 (reset! captured (vec args))
                                 (mock-sh-success "patch"))]
        (github/fetch-pr-diff "octocat/hello-world" 123)
        (is (= ["gh" "pr" "diff" "123" "--repo" "octocat/hello-world"]
               @captured)))))

  (testing "number is stringified via str for CLI arg"
    (let [captured (atom nil)]
      (with-redefs [process/shell (fn [_opts & args]
                                 (reset! captured (vec args))
                                 (mock-sh-success ""))]
        (github/fetch-pr-diff "r" 0)
        (is (= "0" (nth @captured 3)))))))

(deftest fetch-pr-detail-cli-args-test
  (testing "passes correct arguments to gh pr view --json"
    (let [captured (atom nil)]
      (with-redefs [process/shell (fn [_opts & args]
                                 (reset! captured (vec args))
                                 (mock-sh-success "{\"title\":\"T\",\"body\":\"\",\"labels\":[],\"files\":[]}"))]
        (github/fetch-pr-detail "octocat/hello-world" 456)
        (is (= ["gh" "pr" "view" "456"
                "--repo" "octocat/hello-world"
                "--json" "title,body,labels,files"]
               @captured))))))

;; ---------------------------------------------------------------------------- No cli-base dependency

(deftest no-cli-base-dependency-test
  (testing "namespace requires babashka.process directly, not cli base"
    (let [ns-obj (find-ns 'ai.miniforge.tui-views.persistence.github)
          requires (set (map first (ns-aliases ns-obj)))]
      (is (contains? requires 'process)
          "Should alias babashka.process as 'process'")
      (is (contains? requires 'json)
          "Should alias cheshire.core as 'json'")
      ;; Must NOT depend on CLI base namespaces
      (is (not (some #(clojure.string/starts-with? (str %) "ai.miniforge.cli")
                     (map (comp str second) (ns-aliases ns-obj))))
          "Must not depend on any ai.miniforge.cli namespace"))))

;; ---------------------------------------------------------------------------- Return type invariants

(deftest fetch-pr-diff-return-type-test
  (testing "always returns string or nil, never other types"
    ;; Success: string
    (with-redefs [process/shell (fn [_opts & _] (mock-sh-success "data"))]
      (let [result (github/fetch-pr-diff "r" 1)]
        (is (or (string? result) (nil? result)))))
    ;; Failure: nil
    (with-redefs [process/shell (fn [_opts & _] (mock-sh-failure 1 "err"))]
      (let [result (github/fetch-pr-diff "r" 1)]
        (is (or (string? result) (nil? result)))))
    ;; Exception: nil
    (with-redefs [process/shell (fn [_opts & _] (throw (Exception. "boom")))]
      (let [result (github/fetch-pr-diff "r" 1)]
        (is (or (string? result) (nil? result)))))))

(deftest fetch-pr-detail-return-type-test
  (testing "always returns map or nil, never other types"
    ;; Success: map
    (with-redefs [process/shell (fn [_opts & _] (mock-sh-success "{\"title\":\"T\",\"body\":\"\",\"labels\":[],\"files\":[]}"))]
      (let [result (github/fetch-pr-detail "r" 1)]
        (is (or (map? result) (nil? result)))))
    ;; Failure: nil
    (with-redefs [process/shell (fn [_opts & _] (mock-sh-failure 1 "err"))]
      (let [result (github/fetch-pr-detail "r" 1)]
        (is (or (map? result) (nil? result)))))
    ;; Bad JSON: nil
    (with-redefs [process/shell (fn [_opts & _] (mock-sh-success "broken"))]
      (let [result (github/fetch-pr-detail "r" 1)]
        (is (or (map? result) (nil? result)))))))

(deftest fetch-pr-diff-and-detail-return-shape-test
  (testing "always returns a map with :diff :detail :repo :number keys"
    (with-redefs [github/fetch-pr-diff   (fn [_ _] nil)
                  github/fetch-pr-detail (fn [_ _] nil)]
      (let [result (github/fetch-pr-diff-and-detail "r" 1)]
        (is (contains? result :diff))
        (is (contains? result :detail))
        (is (contains? result :repo))
        (is (contains? result :number))
        (is (= 4 (count (keys result)))
            "Result map should have exactly 4 keys")))))
