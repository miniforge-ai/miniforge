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
(ns ai.miniforge.cli.web.github-test
  "Covers the JSON-parse diagnostic paths and the fetch-prs analysis-failure
   propagation behaviour introduced in the PR #1903 fix.

   Each `fetch-*` function is tested independently:
     - malformed JSON → stderr warning (catalog-backed) + safe fallback value
     - (fetch-prs only) analysis failure → exception propagates to caller"
  (:require
   [babashka.process]
   [clojure.test :refer [deftest testing is]]
   [clojure.string :as str]
   [ai.miniforge.cli.web.github :as sut]
   [ai.miniforge.cli.web.risk :as risk]))

;------------------------------------------------------------------------------ Layer 0

;; helpers
(defn- ^{:stratum 0} fake-sh-success
  "Return a mock process/sh result that looks like a successful gh invocation
   with `body` as stdout."
  [body]
  {:exit 0 :out body :err ""})

(defn- ^{:stratum 0} fake-sh-failure
  "Return a mock process/sh result that looks like a failed gh invocation."
  []
  {:exit 1 :out "" :err "gh: not found"})

(defn- ^{:stratum 0} capture-stderr
  "Execute `(f)` with *err* rebound to a fresh StringWriter and return
   [return-value stderr-string].  The github functions emit diagnostics via
   (binding [*out* *err*] (println ...)), so the inner *out* is bound to
   whatever *err* is at call time."
  [f]
  (let [buf (java.io.StringWriter.)
        ret (binding [*err* (java.io.PrintWriter. buf)]
              (f))]
    [ret (.toString buf)]))

;------------------------------------------------------------------------------ Layer 1

;; fetch-prs
(deftest ^{:stratum 1} fetch-prs-returns-analyzed-prs-on-valid-json-test
  (testing "valid JSON is parsed and each PR is enriched with :repo and :analysis"
    (with-redefs [babashka.process/sh (fn [& _] (fake-sh-success "[{\"number\":1}]"))
                  risk/analyze-pr (fn [pr] {:risk :low :pr pr})]
      (let [result (sut/fetch-prs "org/repo")]
        (is (vector? result))
        (is (= 1 (count result)))
        (is (= "org/repo" (:repo (first result))))
        (is (= :low (get-in result [0 :analysis :risk])))))))

(deftest ^{:stratum 1} fetch-prs-returns-empty-vec-and-logs-on-malformed-json-test
  (testing "malformed JSON → [] fallback + catalog-backed warning on stderr"
    (with-redefs [babashka.process/sh (fn [& _] (fake-sh-success "not-valid-json{{}"))]
      (let [[result stderr] (capture-stderr #(sut/fetch-prs "org/repo"))]
        (is (= [] result))
        (is (str/includes? stderr "fetch-prs"))
        (is (str/includes? stderr "JSON parse failed"))
        (is (str/includes? stderr "org/repo"))))))

(deftest ^{:stratum 1} fetch-prs-propagates-analysis-exception-test
  (testing "a risk/analyze-pr failure propagates; it is NOT swallowed as a parse failure"
    (with-redefs [babashka.process/sh (fn [& _] (fake-sh-success "[{\"number\":1}]"))
                  risk/analyze-pr (fn [_] (throw (ex-info "analysis boom" {:code :boom})))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"analysis boom"
                            (sut/fetch-prs "org/repo"))))))

(deftest ^{:stratum 1} fetch-prs-returns-nil-when-gh-fails-test
  (testing "gh CLI failure (non-zero exit) → nil without logging"
    (with-redefs [babashka.process/sh (fn [& _] (fake-sh-failure))]
      (let [[result stderr] (capture-stderr #(sut/fetch-prs "org/repo"))]
        (is (nil? result))
        (is (str/blank? stderr))))))

;; fetch-pr-diff
(deftest ^{:stratum 1} fetch-pr-diff-kills-a-gh-that-does-not-answer-test
  (let [pending (promise)
        destroyed (atom nil)]
    (with-redefs [babashka.process/process (fn [& _] pending)
                  babashka.process/destroy-tree #(reset! destroyed %)
                  sut/gh-timeout-ms 10]
      (is (nil? (sut/fetch-pr-diff "o/r" 7)))
      (is (identical? pending @destroyed) "the hung gh is killed, not left running"))
    (testing "an answer in time is the diff, and nothing is killed"
      (reset! destroyed nil)
      (with-redefs [babashka.process/process (fn [& _] (doto (promise) (deliver (fake-sh-success "d"))))
                    babashka.process/destroy-tree #(reset! destroyed %)]
        (is (= "d" (sut/fetch-pr-diff "o/r" 7)))
        (is (nil? @destroyed))))))

;; fetch-pr-body
(deftest ^{:stratum 1} fetch-pr-body-returns-parsed-map-on-valid-json-test
  (testing "valid JSON is parsed into a map"
    (with-redefs [babashka.process/sh (fn [& _]
                                        (fake-sh-success "{\"title\":\"T\",\"body\":\"B\",\"labels\":[]}"))]
      (is (= {:title "T" :body "B" :labels []}
             (sut/fetch-pr-body "org/repo" 42))))))

(deftest ^{:stratum 1} fetch-pr-body-returns-nil-and-logs-on-malformed-json-test
  (testing "malformed JSON → nil fallback + catalog-backed warning containing repo + PR number"
    (with-redefs [babashka.process/sh (fn [& _] (fake-sh-success "<<<bad json>>>"))]
      (let [[result stderr] (capture-stderr #(sut/fetch-pr-body "org/repo" 42))]
        (is (nil? result))
        (is (str/includes? stderr "fetch-pr-body"))
        (is (str/includes? stderr "JSON parse failed"))
        (is (str/includes? stderr "org/repo"))
        (is (str/includes? stderr "42"))))))

;; fetch-workflow-runs
(deftest ^{:stratum 1} fetch-workflow-runs-returns-parsed-vec-on-valid-json-test
  (testing "valid JSON is returned as a vector of run maps"
    (with-redefs [babashka.process/sh (fn [& _]
                                        (fake-sh-success "[{\"workflowName\":\"CI\"}]"))]
      (is (= [{:workflowName "CI"}]
             (sut/fetch-workflow-runs "org/repo"))))))

(deftest ^{:stratum 1} fetch-workflow-runs-returns-empty-vec-and-logs-on-malformed-json-test
  (testing "malformed JSON → [] fallback + catalog-backed warning on stderr"
    (with-redefs [babashka.process/sh (fn [& _] (fake-sh-success "<html>error page</html>"))]
      (let [[result stderr] (capture-stderr #(sut/fetch-workflow-runs "org/repo"))]
        (is (= [] result))
        (is (str/includes? stderr "fetch-workflow-runs"))
        (is (str/includes? stderr "JSON parse failed"))
        (is (str/includes? stderr "org/repo"))))))

(deftest ^{:stratum 1} fetch-workflow-runs-returns-empty-vec-when-gh-fails-test
  (testing "gh CLI failure (non-zero exit) → [] without logging"
    (with-redefs [babashka.process/sh (fn [& _] (fake-sh-failure))]
      (let [[result stderr] (capture-stderr #(sut/fetch-workflow-runs "org/repo"))]
        (is (= [] result))
        (is (str/blank? stderr))))))
