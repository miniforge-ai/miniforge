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
  "Tests for the github.clj batched-review posting (N13 §2.2)."
  (:require [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [ai.miniforge.dag-executor.interface :as dag]
            [ai.miniforge.pr-lifecycle.github :as github]))

;; ── helpers ──────────────────────────────────────────────────────────

(defn- capture-shell
  "Replace `process/shell` with a stub that records every invocation
   (args + stdin) into `calls-atom` and returns `result`. Returns a
   no-arg fn suitable for `with-redefs`."
  [calls-atom result]
  (fn [opts & args]
    (swap! calls-atom conj {:opts opts :args (vec args)})
    result))

(def ^:private fake-review-success
  (json/generate-string
   {:id 999001 :html_url "https://github.com/o/r/pull/42#pullrequestreview-999001"
    :state "COMMENTED"}))

(def ^:private comment-renderer-shape
  [{:comment/author "miniforge-policy-evaluator[bot]"
    :comment/path   "components/agent/src/foo.clj"
    :comment/line   42
    :comment/body   "**Rule X**\n\n```edn\n:comment/payload\n{:violation/rule-id :x}\n```"}
   {:comment/author "miniforge-policy-evaluator[bot]"
    :comment/path   "components/agent/src/bar.clj"
    :comment/line   7
    :comment/body   "**Rule Y**"}])

(def ^:private flat-shape
  [{:path "src/baz.clj" :line 1 :body "flat-shape body"}])

;; ── tests ────────────────────────────────────────────────────────────

(deftest post-review-uses-create-review-endpoint-via-stdin
  (testing "post-review! shells out to the right gh api endpoint with --input -"
    (let [calls (atom [])
          stub  (capture-shell calls
                               {:exit 0 :out fake-review-success :err ""})]
      (with-redefs [process/shell stub]
        (let [_ (github/post-review! "/some/repo" 42 "summary" comment-renderer-shape)
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

(deftest post-review-translates-renderer-shape-to-github-comments
  (testing "stdin JSON has comments[] with path/line/side/body keys per render record"
    (let [calls (atom [])
          stub  (capture-shell calls
                               {:exit 0 :out fake-review-success :err ""})]
      (with-redefs [process/shell stub]
        (github/post-review! "/some/repo" 42 "summary" comment-renderer-shape))
      (let [stdin (get-in (first @calls) [:opts :in])
            payload (json/parse-string stdin true)]
        (is (= "summary" (:body payload)))
        (is (= "COMMENT" (:event payload)))
        (is (= 2 (count (:comments payload))))
        (is (= {:path "components/agent/src/foo.clj"
                :line 42
                :side "RIGHT"
                :body (-> comment-renderer-shape first :comment/body)}
               (first (:comments payload))))))))

(deftest post-review-accepts-flat-shape-too
  (testing "{:path :line :body} also flows through unchanged"
    (let [calls (atom [])
          stub  (capture-shell calls
                               {:exit 0 :out fake-review-success :err ""})]
      (with-redefs [process/shell stub]
        (github/post-review! "/some/repo" 42 "summary" flat-shape))
      (let [stdin (get-in (first @calls) [:opts :in])
            payload (json/parse-string stdin true)]
        (is (= 1 (count (:comments payload))))
        (is (= {:path "src/baz.clj" :line 1 :side "RIGHT" :body "flat-shape body"}
               (first (:comments payload))))))))

(deftest post-review-success-shape
  (testing "successful gh response is parsed into {:review-id :url :state :comment-count}"
    (let [calls (atom [])
          stub  (capture-shell calls
                               {:exit 0 :out fake-review-success :err ""})]
      (with-redefs [process/shell stub]
        (let [r (github/post-review! "/some/repo" 42 "summary" comment-renderer-shape)]
          (is (dag/ok? r))
          (is (= 999001 (-> r :data :review-id)))
          (is (= "COMMENTED" (-> r :data :state)))
          (is (= 2 (-> r :data :comment-count)))
          (is (re-find #"#pullrequestreview-999001" (-> r :data :url))))))))

(deftest post-review-gh-failure-returns-typed-error
  (testing "non-zero gh exit yields :gh-command-failed with stderr + exit code"
    (let [calls (atom [])
          stub  (capture-shell calls
                               {:exit 1 :out "" :err "HTTP 422: Unprocessable Entity"})]
      (with-redefs [process/shell stub]
        (let [r (github/post-review! "/some/repo" 42 "summary" comment-renderer-shape)]
          (is (not (dag/ok? r)))
          (is (= :gh-command-failed (get-in r [:error :code])))
          (is (re-find #"422" (get-in r [:error :message])))
          (is (= 1 (get-in r [:error :data :exit-code]))))))))

(deftest post-review-shell-exception-returns-typed-error
  (testing "process/shell throwing yields :gh-exception"
    (let [stub (fn [& _] (throw (ex-info "boom" {})))]
      (with-redefs [process/shell stub]
        (let [r (github/post-review! "/some/repo" 42 "summary" comment-renderer-shape)]
          (is (not (dag/ok? r)))
          (is (= :gh-exception (get-in r [:error :code]))))))))
