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
(ns ai.miniforge.gate.precommit-discipline-test
  "Tests for pre-commit discipline policy gate."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ai.miniforge.gate.precommit-discipline :as discipline]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} parse-bypass-reason-test
  (testing "Extracts bypass reason from commit message"
    (is (= "environmental issue"
           (discipline/parse-bypass-reason
            "fix: something\n\n[BYPASS-HOOKS: environmental issue]\n...")))
    
    (is (= "EMERGENCY"
           (discipline/parse-bypass-reason
            "hotfix: production down\n\n[BYPASS-HOOKS: EMERGENCY]\n..."))))
  
  (testing "Returns nil when no bypass marker found"
    (is (nil? (discipline/parse-bypass-reason
               "fix: normal commit\n\nNo bypass here")))))

(deftest ^{:stratum 0} check-manual-validation-test
  (testing "Detects documented manual validation"
    (let [message "fix: thing\n\nManual validation:\n- Tests passed: clojure -M:poly test\n- Linting passed: bb lint:clj"
          result (discipline/check-manual-validation message)]
      (is (:documented? result))
      (is (= 2 (count (:steps result))))
      (is (= "Tests passed: clojure -M:poly test" (first (:steps result))))))
  
  (testing "Returns false when no manual validation documented"
    (let [message "fix: thing\n\nSome other content"
          result (discipline/check-manual-validation message)]
      (is (not (:documented? result)))
      (is (empty? (:steps result))))))

(deftest ^{:stratum 0} check-no-verify-in-history-test
  (testing "Detects bypass markers in commit messages"
    (is (true? (discipline/check-no-verify-in-history?
                {:message "fix: thing\n\n[BYPASS-HOOKS: reason]"})))
    
    (is (true? (discipline/check-no-verify-in-history?
                {:message "fix: thing using --no-verify"})))
    
    (is (true? (discipline/check-no-verify-in-history?
                {:message "fix: had to skip hooks due to..."}))))
  
  (testing "Returns false for normal commits"
    (is (false? (discipline/check-no-verify-in-history?
                 {:message "fix: normal commit\n\nJust a regular fix"})))))

(deftest ^{:stratum 0} validate-bypass-commit-test
  (testing "Valid bypassed commit passes"
    (let [commit {:hash "abc123"
                  :subject "fix: emergency fix"
                  :message "fix: emergency fix\n\n[BYPASS-HOOKS: environmental issue]\n\nManual validation:\n- Tests passed: exit code 0\n- Linting passed: exit code 0\n\nRoot cause: pre-commit hook failing\nWhy bypass: environment issue"}
          result (discipline/validate-bypass-commit commit)]
      (is (:valid? result))
      (is (empty? (filter #(= :error (:severity %)) (:violations result))))))
  
  (testing "Bypassed commit without [BYPASS-HOOKS:] marker fails"
    (let [commit {:hash "abc123"
                  :subject "fix: bypassed hooks"
                  :message "fix: bypassed hooks\n\nUsed --no-verify"}
          result (discipline/validate-bypass-commit commit)]
      (is (not (:valid? result)))
      (is (some #(and (= :error (:severity %))
                      (re-find #"without \[BYPASS-HOOKS: reason\]" (:message %)))
                (:violations result)))))
  
  (testing "Bypassed commit without manual validation fails"
    (let [commit {:hash "abc123"
                  :subject "fix: thing"
                  :message "fix: thing\n\n[BYPASS-HOOKS: reason]\n\nNo manual validation documented"}
          result (discipline/validate-bypass-commit commit)]
      (is (not (:valid? result)))
      (is (some #(and (= :error (:severity %))
                      (re-find #"missing manual validation" (:message %)))
                (:violations result)))))
  
  (testing "Bypassed commit without root cause generates warning"
    (let [commit {:hash "abc123"
                  :subject "fix: thing"
                  :message "fix: thing\n\n[BYPASS-HOOKS: reason]\n\nManual validation:\n- Tests passed"}
          result (discipline/validate-bypass-commit commit)]
      ;; Valid because only warnings, not errors
      (is (:valid? result))
      (is (some #(and (= :warning (:severity %))
                      (re-find #"Root cause:" (:message %)))
                (:violations result))))))

(deftest ^{:stratum 0} check-precommit-discipline-integration-test
  (testing "Gate returns passed for clean history"
    ;; Note: This test will check actual git history if run in a git repo
    ;; In CI/test environments without recent bypass commits, should pass
    (let [result (discipline/check-precommit-discipline
                  {}
                  {:config {:commits-to-check 5}})]
      (is (contains? result :passed?))
      (is (boolean? (:passed? result)))))

  (testing "Gate accepts configuration options"
    (let [result (discipline/check-precommit-discipline
                  {}
                  {:config {:commits-to-check 10
                           :branch "HEAD"
                           :fail-on-warning true}})]
      (is (contains? result :passed?)))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} get-recent-commits-multiline-body-test
  (testing "A commit with a multi-line body is parsed as one commit, not split"
    ;; Fields are NUL-separated (\u0000); records are RS-separated (\u001e).
    ;; Neither byte is legal in git commit messages, so newlines inside a body
    ;; cannot create false record boundaries.
    (let [hash    "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
          fake-out (str hash "\u0000" "feat: multi-line" "\u0000"
                        "Line 1 of body\nLine 2 of body" "\u0000"
                        "Author" "\u0000" "2026-08-30 10:00:00 +0000" "\u001e")]
      (with-redefs [ai.miniforge.gate.precommit-discipline/exec-git
                    (fn [_args] {:exit 0 :out fake-out :err ""})]
        (let [commits (discipline/get-recent-commits :limit 5 :branch "HEAD")]
          (is (= 1 (count commits))
              "Multi-line body must yield exactly one commit map")
          (is (str/includes? (:body (first commits)) "Line 2 of body")
              "Body must include all lines from the multi-line body"))))))

(deftest ^{:stratum 1} get-recent-commits-format-arg-test
  (testing "exec-git receives --format= as a single joined argument"
    ;; Before the fix, `\"--format=\"` and the format string were two separate
    ;; vector elements, so git received them as distinct positional args.
    ;; Git treated the format string as a revision reference and exited 128,
    ;; making get-recent-commits silently return [] on every call.
    (let [received-args (atom nil)]
      (with-redefs [ai.miniforge.gate.precommit-discipline/exec-git
                    (fn [args]
                      (reset! received-args args)
                      {:exit 0 :out "" :err ""})]
        (discipline/get-recent-commits :limit 5 :branch "HEAD")
        (is (some #(str/starts-with? % "--format=") @received-args)
            "exec-git must receive --format=<value> as a single fused argument")
        (is (not (some #(= "--format=" %) @received-args))
            "exec-git must not receive --format= as a bare argument with no value")))))

(deftest ^{:stratum 1} check-precommit-discipline-rejects-undocumented-bypass-test
  (testing "Gate fails when a bypass commit lacks proper documentation"
    ;; Stub exec-git to inject a commit that bypassed hooks without the
    ;; required [BYPASS-HOOKS: reason] marker so the gate exercises the full
    ;; detection + validation path.
    (let [fake-commit (str "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                           "\u0000" "fix: skip hooks" "\u0000"
                           "Used --no-verify because I was in a hurry"
                           "\u0000" "Test Author" "\u0000" "2026-08-30 10:00:00 +0000" "\u001e")]
      (with-redefs [ai.miniforge.gate.precommit-discipline/exec-git
                    (fn [_args] {:exit 0 :out fake-commit :err ""})]
        (let [result (discipline/check-precommit-discipline {} {:config {:commits-to-check 5}})]
          (is (false? (:passed? result))
              "Gate must fail when a bypass commit has no [BYPASS-HOOKS:] marker")
          (is (seq (:errors result))
              "Gate must produce at least one error for an undocumented bypass")))))

(deftest ^{:stratum 1} get-recent-commits-pipe-delimiter-in-body-test
  (testing "||| in body does not shift fields or produce a false-negative gate result"
    ;; Regression for the fail-open parser case reported in review:
    ;; with the old ||| field separator, a body containing ||| shifted subsequent
    ;; fields — the --no-verify text ended up in :author, :message was truncated,
    ;; and check-precommit-discipline returned {:passed? true}.
    ;; With NUL-delimited framing ||| is inert body text.
    (let [hash     (apply str (repeat 40 "a"))
          fake-out (str hash "\u0000"
                        "fix: something" "\u0000"
                        "Copied syntax: |||\nUsed --no-verify without documentation" "\u0000"
                        "Test Author" "\u0000"
                        "2026-08-30 10:00:00 +0000" "\u001e")]
      (with-redefs [ai.miniforge.gate.precommit-discipline/exec-git
                    (fn [_args] {:exit 0 :out fake-out :err ""})]
        (let [commits (discipline/get-recent-commits :limit 5 :branch "HEAD")]
          (is (= 1 (count commits))
              "||| in body must not split the record")
          (is (str/includes? (:body (first commits)) "Used --no-verify")
              "Body must retain --no-verify text that follows ||| in body")
          (is (= "Test Author" (:author (first commits)))
              ":author must not be polluted with body text after |||"))
        (let [result (discipline/check-precommit-discipline {} {:config {:commits-to-check 5}})]
          (is (false? (:passed? result))
              "Gate must fail: --no-verify in body must be detected even when body contains |||"))))))

(deftest ^{:stratum 1} get-recent-commits-hash-length-test
  (testing "SHA-256 (64-hex) hash is accepted"
    (let [hash     (apply str (repeat 64 "c"))
          fake-out (str hash "\u0000" "feat: sha256" "\u0000" "" "\u0000"
                        "Author" "\u0000" "2026-08-30 10:00:00 +0000" "\u001e")]
      (with-redefs [ai.miniforge.gate.precommit-discipline/exec-git
                    (fn [_args] {:exit 0 :out fake-out :err ""})]
        (let [commits (discipline/get-recent-commits :limit 5 :branch "HEAD")]
          (is (= 1 (count commits))
              "SHA-256 64-hex hash must be accepted")
          (is (= hash (:hash (first commits))))))))

  (testing "Intermediate-length hash (41 hex) is rejected"
    ;; {40,64} accepted 41-63 char strings that git never produces.
    ;; The alternation (?:[0-9a-f]{40}|[0-9a-f]{64}) is exact.
    (let [hash     (apply str (repeat 41 "d"))
          fake-out (str hash "\u0000" "feat: bad" "\u0000" "" "\u0000"
                        "Author" "\u0000" "2026-08-30 10:00:00 +0000" "\u001e")]
      (with-redefs [ai.miniforge.gate.precommit-discipline/exec-git
                    (fn [_args] {:exit 0 :out fake-out :err ""})]
        (let [commits (discipline/get-recent-commits :limit 5 :branch "HEAD")]
          (is (empty? commits)
              "41-hex intermediate-length hash must be rejected"))))))

(deftest ^{:stratum 1} get-recent-commits-malformed-record-test
  (testing "Records with fewer than 5 fields are dropped without crashing"
    ;; A truncated record (e.g. missing date) must be dropped; the following
    ;; valid record must still be returned.
    (let [bad-rec  (str (apply str (repeat 40 "e"))
                        "\u0000" "feat: truncated" "\u0000" "body" "\u001e")
          good-rec (str (apply str (repeat 40 "f"))
                        "\u0000" "feat: good" "\u0000" "" "\u0000"
                        "Author" "\u0000" "2026-08-30 10:00:00 +0000" "\u001e")]
      (with-redefs [ai.miniforge.gate.precommit-discipline/exec-git
                    (fn [_args] {:exit 0 :out (str bad-rec good-rec) :err ""})]
        (let [commits (discipline/get-recent-commits :limit 5 :branch "HEAD")]
          (is (= 1 (count commits))
              "Malformed record must be dropped; valid record must survive")
          (is (= (apply str (repeat 40 "f")) (:hash (first commits)))))))))
