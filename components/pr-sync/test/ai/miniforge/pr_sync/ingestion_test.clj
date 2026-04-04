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

(ns ai.miniforge.pr-sync.ingestion-test
  "Integration tests for hardened PR ingestion.
   Tests flaky provider responses, partial sync, and error recovery."
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.java.shell]
   [ai.miniforge.pr-sync.core :as core]))

;; ─────────────────────────────────────────────────────────────────────────────
;; Test helpers

(defn temp-config-path []
  (let [f (java.io.File/createTempFile "miniforge-ingest-test" ".edn")]
    (.deleteOnExit f)
    (.getAbsolutePath f)))

(def github-pr-json
  "[{\"number\":1,\"title\":\"Fix bug\",\"url\":\"https://github.com/acme/app/pull/1\",\"state\":\"OPEN\",\"headRefName\":\"fix/bug\",\"isDraft\":false,\"reviewDecision\":\"APPROVED\",\"statusCheckRollup\":[{\"conclusion\":\"SUCCESS\"}]}]")

(def github-pr-json-2
  "[{\"number\":5,\"title\":\"Add feature\",\"url\":\"https://github.com/other/lib/pull/5\",\"state\":\"OPEN\",\"headRefName\":\"feat/new\",\"isDraft\":false,\"reviewDecision\":null,\"statusCheckRollup\":[]}]")

(def github-multi-pr-json
  "[{\"number\":1,\"title\":\"PR one\",\"url\":\"https://github.com/acme/app/pull/1\",\"state\":\"OPEN\",\"headRefName\":\"fix/one\",\"isDraft\":false,\"reviewDecision\":null,\"statusCheckRollup\":[]},{\"number\":2,\"title\":\"PR two\",\"url\":\"https://github.com/acme/app/pull/2\",\"state\":\"OPEN\",\"headRefName\":\"fix/two\",\"isDraft\":true,\"reviewDecision\":null,\"statusCheckRollup\":[]}]")

(defn make-sh-router
  "Build a mock sh function that routes calls based on repo slug presence in args.
   routes is a map of {substring -> {:exit N :out S :err S}}."
  [routes]
  (fn [& args]
    (let [match (some (fn [[substr response]]
                        (when (some #(and (string? %) (.contains ^String % substr)) args)
                          response))
                      routes)]
      (or match {:exit 1 :out "" :err "no route matched"}))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Partial sync tests — one or more repos fail while others succeed
;; ─────────────────────────────────────────────────────────────────────────────

(deftest fetch-fleet-with-status-partial-sync-test
  (testing "Partial sync: one repo succeeds, one fails — both get status entries"
    (let [path (temp-config-path)]
      (spit path (pr-str {:fleet {:repos ["acme/app" "broken/repo"]}}))
      (with-redefs [clojure.java.shell/sh
                    (fn [& args]
                      (cond
                        ;; acme/app succeeds
                        (and (= "gh" (first args))
                             (some #(and (string? %) (.contains ^String % "acme/app")) args))
                        {:exit 0 :out github-pr-json :err ""}

                        ;; broken/repo fails with auth error
                        (and (= "gh" (first args))
                             (some #(and (string? %) (.contains ^String % "broken/repo")) args))
                        {:exit 1 :out "" :err "HTTP 401 Bad credentials"}

                        :else
                        {:exit 1 :out "" :err "unexpected"}))]
        (let [result (core/fetch-fleet-with-status {:config-path path})]
          ;; PRs from successful repo are present
          (is (= 1 (count (:prs result))))
          (is (= "acme/app" (:pr/repo (first (:prs result)))))

          ;; Both repos have sync status entries
          (is (= 2 (count (:sync-statuses result))))

          ;; Summary shows partial success
          (is (= 1 (get-in result [:summary :ok])))
          (is (= 1 (get-in result [:summary :errors])))
          (is (true? (get-in result [:summary :partial?])))
          (is (false? (get-in result [:summary :all-ok?])))

          ;; Failed repo has classified error
          (let [failed (first (filter #(= :error (:status %)) (:sync-statuses result)))]
            (is (= "broken/repo" (:repo failed)))
            (is (= :auth-failure (:error-category failed)))
            (is (string? (:hint failed)))))))))

(deftest fetch-fleet-with-status-partial-sync-three-repos-test
  (testing "Three repos: two succeed, one fails — partial summary"
    (let [path (temp-config-path)]
      (spit path (pr-str {:fleet {:repos ["acme/app" "other/lib" "broken/repo"]}}))
      (with-redefs [clojure.java.shell/sh
                    (make-sh-router
                     {"acme/app"    {:exit 0 :out github-pr-json :err ""}
                      "other/lib"   {:exit 0 :out github-pr-json-2 :err ""}
                      "broken/repo" {:exit 1 :out "" :err "HTTP 403 Forbidden"}})]
        (let [result (core/fetch-fleet-with-status {:config-path path})]
          (is (= 2 (count (:prs result))))
          (is (= 3 (count (:sync-statuses result))))
          (is (= 2 (get-in result [:summary :ok])))
          (is (= 1 (get-in result [:summary :errors])))
          (is (true? (get-in result [:summary :partial?])))
          (is (= 3 (get-in result [:summary :total]))))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; All-fail scenario
;; ─────────────────────────────────────────────────────────────────────────────

(deftest fetch-fleet-with-status-all-fail-test
  (testing "All repos fail — returns empty PRs with full error details"
    (let [path (temp-config-path)]
      (spit path (pr-str {:fleet {:repos ["a/b" "c/d"]}}))
      (with-redefs [clojure.java.shell/sh
                    (fn [& _args]
                      {:exit 1 :out "" :err "ECONNREFUSED"})]
        (let [result (core/fetch-fleet-with-status {:config-path path})]
          (is (empty? (:prs result)))
          (is (= 2 (count (:sync-statuses result))))
          (is (true? (get-in result [:summary :none-ok?])))
          (is (= 0 (get-in result [:summary :ok])))
          (is (false? (get-in result [:summary :all-ok?]))))))))

(deftest fetch-fleet-with-status-all-fail-distinct-errors-test
  (testing "All repos fail with different errors — each error classified independently"
    (let [path (temp-config-path)]
      (spit path (pr-str {:fleet {:repos ["a/b" "c/d"]}}))
      (with-redefs [clojure.java.shell/sh
                    (make-sh-router
                     {"a/b" {:exit 1 :out "" :err "HTTP 401 Bad credentials"}
                      "c/d" {:exit 1 :out "" :err "API rate limit exceeded"}})]
        (let [result (core/fetch-fleet-with-status {:config-path path})
              statuses (:sync-statuses result)
              categories (set (map :error-category statuses))]
          (is (= 2 (count statuses)))
          (is (contains? categories :auth-failure))
          (is (contains? categories :rate-limited)))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; All-succeed scenario
;; ─────────────────────────────────────────────────────────────────────────────

(deftest fetch-fleet-with-status-all-succeed-test
  (testing "All repos succeed — all-ok summary"
    (let [path (temp-config-path)]
      (spit path (pr-str {:fleet {:repos ["acme/app" "other/lib"]}}))
      (with-redefs [clojure.java.shell/sh
                    (make-sh-router
                     {"acme/app"  {:exit 0 :out github-pr-json :err ""}
                      "other/lib" {:exit 0 :out github-pr-json-2 :err ""}})]
        (let [result (core/fetch-fleet-with-status {:config-path path})]
          (is (= 2 (count (:prs result))))
          (is (true? (get-in result [:summary :all-ok?])))
          (is (false? (get-in result [:summary :partial?])))
          (is (false? (get-in result [:summary :none-ok?])))
          (is (= 2 (get-in result [:summary :ok])))
          (is (= 0 (get-in result [:summary :errors]))))))))

(deftest fetch-fleet-with-status-all-succeed-multi-prs-test
  (testing "Repo returning multiple PRs — all PRs collected"
    (let [path (temp-config-path)]
      (spit path (pr-str {:fleet {:repos ["acme/app"]}}))
      (with-redefs [clojure.java.shell/sh
                    (make-sh-router
                     {"acme/app" {:exit 0 :out github-multi-pr-json :err ""}})]
        (let [result (core/fetch-fleet-with-status {:config-path path})]
          (is (= 2 (count (:prs result))))
          (is (every? #(= "acme/app" (:pr/repo %)) (:prs result)))
          (is (true? (get-in result [:summary :all-ok?]))))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Empty / edge-case fleet configs
;; ─────────────────────────────────────────────────────────────────────────────

(deftest fetch-fleet-with-status-empty-fleet-test
  (testing "Empty fleet — returns empty result with clean summary"
    (let [path (temp-config-path)]
      (spit path (pr-str {:fleet {:repos []}}))
      (let [result (core/fetch-fleet-with-status {:config-path path})]
        (is (empty? (:prs result)))
        (is (empty? (:sync-statuses result)))
        (is (true? (get-in result [:summary :all-ok?])))
        (is (= 0 (get-in result [:summary :total])))))))

(deftest fetch-fleet-with-status-missing-config-test
  (testing "Missing config file — treated as empty fleet, no crash"
    (let [result (core/fetch-fleet-with-status
                  {:config-path "/tmp/nonexistent-miniforge-ingest-42.edn"})]
      (is (empty? (:prs result)))
      (is (empty? (:sync-statuses result)))
      (is (true? (get-in result [:summary :all-ok?]))))))

(deftest fetch-fleet-with-status-single-repo-success-test
  (testing "Single repo fleet with success — summary counts correct"
    (let [path (temp-config-path)]
      (spit path (pr-str {:fleet {:repos ["acme/app"]}}))
      (with-redefs [clojure.java.shell/sh
                    (make-sh-router
                     {"acme/app" {:exit 0 :out github-pr-json :err ""}})]
        (let [result (core/fetch-fleet-with-status {:config-path path})]
          (is (= 1 (count (:prs result))))
          (is (= 1 (count (:sync-statuses result))))
          (is (= 1 (get-in result [:summary :total])))
          (is (= 1 (get-in result [:summary :ok])))
          (is (= 0 (get-in result [:summary :errors]))))))))

(deftest fetch-fleet-with-status-repo-with-no-open-prs-test
  (testing "Repo returns empty PR list — success with zero PRs"
    (let [path (temp-config-path)]
      (spit path (pr-str {:fleet {:repos ["acme/app"]}}))
      (with-redefs [clojure.java.shell/sh
                    (make-sh-router
                     {"acme/app" {:exit 0 :out "[]" :err ""}})]
        (let [result (core/fetch-fleet-with-status {:config-path path})]
          (is (empty? (:prs result)))
          (is (= 1 (count (:sync-statuses result))))
          (is (true? (get-in result [:summary :all-ok?]))))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Per-repo status tests (fetch-repo-with-status)
;; ─────────────────────────────────────────────────────────────────────────────

(deftest fetch-repo-with-status-flaky-provider-test
  (testing "Flaky provider returns error — sync status captures it"
    (with-redefs [clojure.java.shell/sh
                  (fn [& _args]
                    {:exit 1 :out "" :err "API rate limit exceeded"})]
      (let [status (core/fetch-repo-with-status "acme/app")]
        (is (= :error (:status status)))
        (is (= "acme/app" (:repo status)))
        (is (= :rate-limited (:error-category status)))
        (is (string? (:hint status))))))

  (testing "Provider returns malformed JSON — sync status captures parse error"
    (with-redefs [clojure.java.shell/sh
                  (fn [& _args]
                    {:exit 0 :out "NOT JSON" :err ""})]
      (let [status (core/fetch-repo-with-status "acme/app")]
        (is (= :error (:status status)))
        (is (= :parse-error (:error-category status))))))

  (testing "Provider succeeds — sync status captures success"
    (with-redefs [clojure.java.shell/sh
                  (fn [& _args]
                    {:exit 0 :out github-pr-json :err ""})]
      (let [status (core/fetch-repo-with-status "acme/app")]
        (is (= :ok (:status status)))
        (is (= 1 (:pr-count status)))
        (is (vector? (:prs status)))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Error classification — each error category
;; ─────────────────────────────────────────────────────────────────────────────

(deftest fetch-repo-with-status-auth-failure-test
  (testing "401 Bad credentials classified as :auth-failure"
    (with-redefs [clojure.java.shell/sh
                  (fn [& _args]
                    {:exit 1 :out "" :err "HTTP 401 Bad credentials"})]
      (let [status (core/fetch-repo-with-status "acme/app")]
        (is (= :auth-failure (:error-category status))))))

  (testing "403 Forbidden classified as :auth-failure"
    (with-redefs [clojure.java.shell/sh
                  (fn [& _args]
                    {:exit 1 :out "" :err "HTTP 403 Forbidden"})]
      (let [status (core/fetch-repo-with-status "acme/app")]
        (is (= :auth-failure (:error-category status)))))))

(deftest fetch-repo-with-status-rate-limited-test
  (testing "Rate limit message classified as :rate-limited"
    (with-redefs [clojure.java.shell/sh
                  (fn [& _args]
                    {:exit 1 :out "" :err "API rate limit exceeded"})]
      (let [status (core/fetch-repo-with-status "acme/app")]
        (is (= :rate-limited (:error-category status)))
        (is (string? (:hint status)))))))

(deftest fetch-repo-with-status-network-error-test
  (testing "Connection refused classified as network/unknown error"
    (with-redefs [clojure.java.shell/sh
                  (fn [& _args]
                    {:exit 1 :out "" :err "ECONNREFUSED"})]
      (let [status (core/fetch-repo-with-status "acme/app")]
        (is (= :error (:status status)))
        (is (some? (:error-category status)))))))

(deftest fetch-repo-with-status-not-found-test
  (testing "404 Not Found gets appropriate classification"
    (with-redefs [clojure.java.shell/sh
                  (fn [& _args]
                    {:exit 1 :out "" :err "HTTP 404 Not Found"})]
      (let [status (core/fetch-repo-with-status "acme/app")]
        (is (= :error (:status status)))
        (is (some? (:error-category status)))
        (is (= "acme/app" (:repo status)))))))

(deftest fetch-repo-with-status-empty-json-array-test
  (testing "Empty JSON array is a successful sync with zero PRs"
    (with-redefs [clojure.java.shell/sh
                  (fn [& _args]
                    {:exit 0 :out "[]" :err ""})]
      (let [status (core/fetch-repo-with-status "acme/app")]
        (is (= :ok (:status status)))
        (is (= 0 (:pr-count status)))
        (is (empty? (:prs status)))))))

(deftest fetch-repo-with-status-empty-output-test
  (testing "Exit 0 with empty output classified as parse error or empty"
    (with-redefs [clojure.java.shell/sh
                  (fn [& _args]
                    {:exit 0 :out "" :err ""})]
      (let [status (core/fetch-repo-with-status "acme/app")]
        ;; Either error/parse-error or ok with empty — implementation decides
        (is (contains? #{:ok :error} (:status status)))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Exception safety tests
;; ─────────────────────────────────────────────────────────────────────────────

(deftest fetch-repo-with-status-exception-safety-test
  (testing "Runtime exception in provider call is caught and classified"
    (with-redefs [clojure.java.shell/sh
                  (fn [& _args]
                    (throw (Exception. "connection reset by peer")))]
      (let [status (core/fetch-repo-with-status "acme/app")]
        (is (= :error (:status status)))
        (is (string? (:error status)))
        (is (some? (:error-category status)))))))

(deftest fetch-repo-with-status-npe-safety-test
  (testing "NullPointerException in provider call is caught gracefully"
    (with-redefs [clojure.java.shell/sh
                  (fn [& _args]
                    (throw (NullPointerException. "simulated NPE")))]
      (let [status (core/fetch-repo-with-status "acme/app")]
        (is (= :error (:status status)))
        (is (some? (:error-category status)))))))

(deftest fetch-fleet-with-status-exception-in-one-repo-test
  (testing "Exception in one repo does not crash the entire fleet sync"
    (let [path (temp-config-path)]
      (spit path (pr-str {:fleet {:repos ["acme/app" "crash/repo"]}}))
      (with-redefs [clojure.java.shell/sh
                    (fn [& args]
                      (cond
                        (some #(and (string? %) (.contains ^String % "acme/app")) args)
                        {:exit 0 :out github-pr-json :err ""}

                        (some #(and (string? %) (.contains ^String % "crash/repo")) args)
                        (throw (RuntimeException. "provider exploded"))

                        :else
                        {:exit 1 :out "" :err "unexpected"}))]
        (let [result (core/fetch-fleet-with-status {:config-path path})]
          ;; Successful repo's PRs still present
          (is (= 1 (count (:prs result))))
          (is (= "acme/app" (:pr/repo (first (:prs result)))))
          ;; Failed repo captured in statuses
          (is (= 2 (count (:sync-statuses result))))
          (is (true? (get-in result [:summary :partial?]))))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Status entry structure validation
;; ─────────────────────────────────────────────────────────────────────────────

(deftest fetch-repo-with-status-ok-structure-test
  (testing "Successful status entry has expected keys"
    (with-redefs [clojure.java.shell/sh
                  (fn [& _args]
                    {:exit 0 :out github-pr-json :err ""})]
      (let [status (core/fetch-repo-with-status "acme/app")]
        (is (= :ok (:status status)))
        (is (= "acme/app" (:repo status)))
        (is (integer? (:pr-count status)))
        (is (vector? (:prs status)))
        ;; PRs have expected train-pr keys
        (let [pr (first (:prs status))]
          (is (= 1 (:pr/number pr)))
          (is (= "Fix bug" (:pr/title pr))))))))

(deftest fetch-repo-with-status-error-structure-test
  (testing "Error status entry has expected keys"
    (with-redefs [clojure.java.shell/sh
                  (fn [& _args]
                    {:exit 1 :out "" :err "HTTP 401 Bad credentials"})]
      (let [status (core/fetch-repo-with-status "acme/app")]
        (is (= :error (:status status)))
        (is (= "acme/app" (:repo status)))
        (is (keyword? (:error-category status)))
        (is (string? (:hint status)))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Summary invariant tests
;; ─────────────────────────────────────────────────────────────────────────────

(deftest summary-invariants-test
  (testing "Summary :ok + :errors = :total always holds"
    (let [path (temp-config-path)]
      (spit path (pr-str {:fleet {:repos ["acme/app" "broken/repo" "other/lib"]}}))
      (with-redefs [clojure.java.shell/sh
                    (make-sh-router
                     {"acme/app"    {:exit 0 :out github-pr-json :err ""}
                      "broken/repo" {:exit 1 :out "" :err "HTTP 500 Internal Server Error"}
                      "other/lib"   {:exit 0 :out github-pr-json-2 :err ""}})]
        (let [result (core/fetch-fleet-with-status {:config-path path})
              summary (:summary result)]
          (is (= (:total summary)
                 (+ (:ok summary) (:errors summary))))
          ;; partial? is true iff some ok and some errors
          (is (= (:partial? summary)
                 (and (pos? (:ok summary)) (pos? (:errors summary)))))
          ;; all-ok? and none-ok? are mutually exclusive of partial?
          (is (not (and (:all-ok? summary) (:partial? summary))))
          (is (not (and (:none-ok? summary) (:partial? summary)))))))))

(deftest summary-boolean-flags-all-ok-test
  (testing "all-ok? is true only when errors = 0 and total > 0"
    (let [path (temp-config-path)]
      (spit path (pr-str {:fleet {:repos ["acme/app"]}}))
      (with-redefs [clojure.java.shell/sh
                    (make-sh-router
                     {"acme/app" {:exit 0 :out github-pr-json :err ""}})]
        (let [summary (get-in (core/fetch-fleet-with-status {:config-path path}) [:summary])]
          (is (true? (:all-ok? summary)))
          (is (false? (:partial? summary)))
          (is (false? (:none-ok? summary))))))))

(deftest summary-boolean-flags-none-ok-test
  (testing "none-ok? is true only when ok = 0 and total > 0"
    (let [path (temp-config-path)]
      (spit path (pr-str {:fleet {:repos ["a/b"]}}))
      (with-redefs [clojure.java.shell/sh
                    (fn [& _args] {:exit 1 :out "" :err "timeout"})]
        (let [summary (get-in (core/fetch-fleet-with-status {:config-path path}) [:summary])]
          (is (true? (:none-ok? summary)))
          (is (false? (:all-ok? summary)))
          (is (false? (:partial? summary))))))))
