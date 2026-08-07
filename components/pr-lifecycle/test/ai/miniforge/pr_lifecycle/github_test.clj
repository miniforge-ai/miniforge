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
(ns ai.miniforge.pr-lifecycle.github-test
  "Tests for GitHub provider readback and batched-review posting."
  (:require [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ai.miniforge.dag-executor.interface :as dag]
            [ai.miniforge.pr-lifecycle.github :as github]))

;------------------------------------------------------------------------------ Layer 0

;; ── helpers ──────────────────────────────────────────────────────────
(defn- ^{:stratum 0} capture-shell
  "Replace `process/shell` with a stub that records every invocation
   (args + stdin) into `calls-atom` and returns `result`. Returns a
   no-arg fn suitable for `with-redefs`."
  [calls-atom result]
  (fn [opts & args]
    (swap! calls-atom conj {:opts opts :args (vec args)})
    result))

(def ^{:stratum 0} ^:private fake-review-success
  (json/generate-string
   {:id 999001 :html_url "https://github.com/o/r/pull/42#pullrequestreview-999001"
    :state "COMMENTED"}))

(def ^{:stratum 0} ^:private fixture-sha "deadbeefcafef00ddeadbeefcafef00ddeadbeef")

(def ^{:stratum 0} ^:private comment-renderer-shape
  [{:comment/author "miniforge-policy-evaluator[bot]"
    :comment/path   "components/agent/src/foo.clj"
    :comment/line   42
    :comment/body   "**Rule X**\n\n```edn\n:comment/payload\n{:violation/rule-id :x}\n```"}
   {:comment/author "miniforge-policy-evaluator[bot]"
    :comment/path   "components/agent/src/bar.clj"
    :comment/line   7
    :comment/body   "**Rule Y**"}])

(def ^{:stratum 0} ^:private flat-shape
  [{:path "src/baz.clj" :line 1 :body "flat-shape body"}])

(defn- ^{:stratum 0} review-thread-page
  "Build one GraphQL response page without duplicating provider maps."
  [nodes has-next? end-cursor]
  (dag/ok
   {:data
    {:repository
     {:pullRequest
      {:reviewThreads
       {:nodes nodes
        :pageInfo {:hasNextPage has-next?
                   :endCursor end-cursor}}}}}}))

(deftest ^{:stratum 0} unresolved-review-threads-rejects-incomplete-readback
  (with-redefs [github/run-gh-command
                (fn [_ _] (dag/ok {:output "git@github.com:miniforge-ai/miniforge.git"}))
                github/graphql-query
                (fn [& _]
                  (dag/ok {:data {:repository {:pullRequest nil}}}))]
    (let [result (github/unresolved-review-threads "/repo" 1703)]
      (is (dag/err? result))
      (is (= :invalid-review-thread-response (get-in result [:error :code]))))))

(deftest ^{:stratum 0} unresolved-review-threads-propagates-provider-failure
  (let [failure (dag/err :graphql-error "provider unavailable")]
    (with-redefs [github/run-gh-command
                  (fn [_ _] (dag/ok {:output "git@github.com:miniforge-ai/miniforge.git"}))
                  github/graphql-query (fn [& _] failure)]
      (is (= failure (github/unresolved-review-threads "/repo" 1703))))))

;------------------------------------------------------------------------------ Layer 1

;; ── tests ────────────────────────────────────────────────────────────
(deftest ^{:stratum 1} unresolved-review-threads-paginates
  (let [requests (atom [])
        pages [(review-thread-page [{:id "resolved" :isResolved true}]
                                   true "next-page")
               (review-thread-page [{:id "open" :isResolved false}]
                                   false nil)]]
    (with-redefs [github/run-gh-command
                  (fn [_ _]
                    (dag/ok {:output "https://github.com/acme/repo.with-dots.git"}))
                  github/graphql-query
                  (fn [_ _ & {:keys [variables]}]
                    (let [index (count @requests)]
                      (swap! requests conj variables)
                      (nth pages index)))]
      (let [result (github/unresolved-review-threads "/repo" 1703)]
        (is (dag/ok? result))
        (is (= {:has-unresolved? true :unresolved-count 1}
               (:data result)))
        (is (= [nil "next-page"] (mapv :cursor @requests)))
        (is (= {:owner "acme" :repo "repo.with-dots" :pr 1703}
               (first @requests)))))))

(deftest ^{:stratum 1} unresolved-review-threads-rejects-missing-page-cursor
  (with-redefs [github/run-gh-command
                (fn [_ _] (dag/ok {:output "git@github.com:miniforge-ai/miniforge.git"}))
                github/graphql-query
                (fn [& _]
                  (review-thread-page [] true nil))]
    (let [result (github/unresolved-review-threads "/repo" 1703)]
      (is (dag/err? result))
      (is (= :invalid-pagination (get-in result [:error :code]))))))

(deftest ^{:stratum 1} unresolved-review-threads-rejects-repeated-page-cursor
  (with-redefs [github/run-gh-command
                (fn [_ _] (dag/ok {:output "git@github.com:miniforge-ai/miniforge.git"}))
                github/graphql-query
                (fn [& _]
                  (review-thread-page [] true "same-page"))]
    (let [result (github/unresolved-review-threads "/repo" 1703)]
      (is (dag/err? result))
      (is (= :invalid-pagination (get-in result [:error :code]))))))

(deftest ^{:stratum 1} get-thread-id-follows-reply-root-and-paginates
  (let [requests (atom [])
        thread (fn [id root-id resolved?]
                 {:id id
                  :isResolved resolved?
                  :comments {:nodes [{:databaseId root-id}]}})
        pages [(review-thread-page [(thread "other" 1 true)] true "next-page")
               (review-thread-page [(thread "target" 10 false)] false nil)]]
    (with-redefs [github/run-gh-command
                  (fn [args _]
                    (if (= ["git" "config" "--get" "remote.origin.url"] args)
                      (dag/ok {:output "git@github.com:miniforge-ai/miniforge.git"})
                      (dag/ok {:output (json/generate-string
                                        {:id 99 :in_reply_to_id 10})})))
                  github/graphql-query
                  (fn [_ _ & {:keys [variables]}]
                    (let [index (count @requests)]
                      (swap! requests conj variables)
                      (nth pages index)))]
      (let [result (github/get-thread-id "/repo" 1704 99)]
        (is (= {:thread-id "target" :is-resolved false} (:data result)))
        (is (= [nil "next-page"] (mapv :cursor @requests)))))))

(deftest ^{:stratum 1} get-thread-id-uses-original-comment-id
  (with-redefs [github/run-gh-command
                (fn [args _]
                  (if (= "git" (first args))
                    (dag/ok {:output "git@github.com:miniforge-ai/miniforge.git"})
                    (dag/ok {:output (json/generate-string
                                      {:id 10 :in_reply_to_id nil})})))
                github/graphql-query
                (fn [& _]
                  (review-thread-page
                   [{:id "target" :isResolved true
                     :comments {:nodes [{:databaseId 10}]}}]
                   false nil))]
    (is (= {:thread-id "target" :is-resolved true}
           (:data (github/get-thread-id "/repo" 1704 10))))))

(deftest ^{:stratum 1} post-review-uses-create-review-endpoint-via-stdin
  (testing "post-review! shells out to the right gh api endpoint with --input -"
    (let [calls (atom [])
          stub  (capture-shell calls
                               {:exit 0 :out fake-review-success :err ""})]
      (with-redefs [process/shell stub]
        (let [_ (github/post-review! "/some/repo" 42 fixture-sha "summary" comment-renderer-shape)
              call (first @calls)]
          (is (= 1 (count @calls)))
          (is (= ["gh" "api"
                  "repos/{owner}/{repo}/pulls/42/reviews"
                  "-X" "POST" "--input" "-"]
                 (:args call)))
          (is (= "/some/repo" (str (get-in call [:opts :dir])))
              "gh runs in the worktree dir so {owner}/{repo} resolves")
          (is (= :string (get-in call [:opts :out])))
          (is (= true (get-in call [:opts :continue]))
              "exit codes returned, not thrown"))))))

(deftest ^{:stratum 1} post-review-translates-renderer-shape-to-github-comments
  (testing "stdin JSON has comments[] with path/line/side/body keys per render record + commit_id"
    (let [calls (atom [])
          stub  (capture-shell calls
                               {:exit 0 :out fake-review-success :err ""})]
      (with-redefs [process/shell stub]
        (github/post-review! "/some/repo" 42 fixture-sha "summary" comment-renderer-shape))
      (let [stdin (get-in (first @calls) [:opts :in])
            payload (json/parse-string stdin true)]
        (is (str/includes? (:body payload) "summary"))
        (is (str/includes? (:body payload) github/review-marker)
            "every posted review body MUST embed the review-marker so the scheduler can dedup against existing reviews")
        (is (= "COMMENT" (:event payload)))
        (is (= fixture-sha (:commit_id payload))
            "commit_id (PR head SHA) MUST be present — GitHub 422s without it on inline comments[]")
        (is (= 2 (count (:comments payload))))
        (is (= {:path "components/agent/src/foo.clj"
                :line 42
                :side "RIGHT"
                :body (-> comment-renderer-shape first :comment/body)}
               (first (:comments payload))))))))

(deftest ^{:stratum 1} post-review-marker-is-idempotent
  (testing "supplied body that already contains the marker isn't double-tagged"
    (let [calls (atom [])
          stub  (capture-shell calls
                               {:exit 0 :out fake-review-success :err ""})
          pre   (str "summary\n\n" github/review-marker)]
      (with-redefs [process/shell stub]
        (github/post-review! "/some/repo" 42 fixture-sha pre comment-renderer-shape))
      (let [stdin (get-in (first @calls) [:opts :in])
            payload (json/parse-string stdin true)
            body (:body payload)
            n (count (re-seq (re-pattern (java.util.regex.Pattern/quote github/review-marker)) body))]
        (is (= 1 n) "marker appears exactly once even when caller pre-marked")))))

(deftest ^{:stratum 1} post-review-rejects-missing-commit-id
  (testing "missing/blank commit-id short-circuits with :missing-commit-id, never shells out"
    (let [calls (atom [])
          stub  (capture-shell calls {:exit 0 :out "" :err ""})]
      (doseq [bad [nil "" "   "]]
        (with-redefs [process/shell stub]
          (let [r (github/post-review! "/some/repo" 42 bad "summary" comment-renderer-shape)]
            (is (not (dag/ok? r)))
            (is (= :missing-commit-id (get-in r [:error :code]))
                (str "input was " (pr-str bad))))))
      (is (zero? (count @calls))
          "no shell invocation when commit-id missing — fail fast"))))

(deftest ^{:stratum 1} post-review-accepts-flat-shape-too
  (testing "{:path :line :body} also flows through unchanged"
    (let [calls (atom [])
          stub  (capture-shell calls
                               {:exit 0 :out fake-review-success :err ""})]
      (with-redefs [process/shell stub]
        (github/post-review! "/some/repo" 42 fixture-sha "summary" flat-shape))
      (let [stdin (get-in (first @calls) [:opts :in])
            payload (json/parse-string stdin true)]
        (is (= 1 (count (:comments payload))))
        (is (= {:path "src/baz.clj" :line 1 :side "RIGHT" :body "flat-shape body"}
               (first (:comments payload))))))))

(deftest ^{:stratum 1} post-review-success-shape
  (testing "successful gh response is parsed into {:review-id :url :state :comment-count}"
    (let [calls (atom [])
          stub  (capture-shell calls
                               {:exit 0 :out fake-review-success :err ""})]
      (with-redefs [process/shell stub]
        (let [r (github/post-review! "/some/repo" 42 fixture-sha "summary" comment-renderer-shape)]
          (is (dag/ok? r))
          (is (= 999001 (-> r :data :review-id)))
          (is (= "COMMENTED" (-> r :data :state)))
          (is (= 2 (-> r :data :comment-count)))
          (is (re-find #"#pullrequestreview-999001" (-> r :data :url))))))))

(deftest ^{:stratum 1} post-review-gh-failure-returns-typed-error
  (testing "non-zero gh exit yields :gh-command-failed with stderr + exit code"
    (let [calls (atom [])
          stub  (capture-shell calls
                               {:exit 1 :out "" :err "HTTP 422: Unprocessable Entity"})]
      (with-redefs [process/shell stub]
        (let [r (github/post-review! "/some/repo" 42 fixture-sha "summary" comment-renderer-shape)]
          (is (not (dag/ok? r)))
          (is (= :gh-command-failed (get-in r [:error :code])))
          (is (re-find #"422" (get-in r [:error :message])))
          (is (= 1 (get-in r [:error :data :exit-code]))))))))

(deftest ^{:stratum 1} post-review-shell-exception-returns-typed-error
  (testing "process/shell throwing yields :gh-exception"
    (let [stub (fn [& _] (throw (ex-info "boom" {})))]
      (with-redefs [process/shell stub]
        (let [r (github/post-review! "/some/repo" 42 fixture-sha "summary" comment-renderer-shape)]
          (is (not (dag/ok? r)))
          (is (= :gh-exception (get-in r [:error :code]))))))))
