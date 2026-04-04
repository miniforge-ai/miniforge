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

(ns ai.miniforge.pr-sync.core-test
  "Comprehensive unit tests for pr-sync core.
   Covers Layer 0 pure helpers, Layer 1 fleet config I/O,
   Layer 2 provider CLI interaction (mocked), and Layer 4 discovery."
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.string :as str]
   [clojure.java.shell]
   [ai.miniforge.pr-sync.core :as core]))

;; ============================================================================
;; Test helpers
;; ============================================================================

(defn temp-config-path []
  (let [f (java.io.File/createTempFile "miniforge-core-test" ".edn")]
    (.deleteOnExit f)
    (.getAbsolutePath f)))

(defn make-sh-router
  "Build a mock sh function that routes based on substring matches in args."
  [routes]
  (fn [& args]
    (let [match (some (fn [[substr response]]
                        (when (some #(and (string? %) (.contains ^String % substr)) args)
                          response))
                      routes)]
      (or match {:exit 1 :out "" :err "no route matched"}))))

;; ============================================================================
;; Layer 0: Pure helpers
;; ============================================================================

(deftest normalize-repo-slug-test
  (testing "Trims whitespace and lowercases"
    (is (= "owner/repo" (core/normalize-repo-slug "  Owner/Repo  "))))

  (testing "Handles nil gracefully"
    (is (= "" (core/normalize-repo-slug nil))))

  (testing "Already normalized input is unchanged"
    (is (= "acme/app" (core/normalize-repo-slug "acme/app"))))

  (testing "GitLab prefix preserved in lowercase"
    (is (= "gitlab:group/project" (core/normalize-repo-slug "GitLab:Group/Project")))))

(deftest valid-repo-slug-test
  (testing "Valid GitHub slugs"
    (is (true? (core/valid-repo-slug? "owner/repo")))
    (is (true? (core/valid-repo-slug? "my-org/my-repo.js")))
    (is (true? (core/valid-repo-slug? "A_B/C.D"))))

  (testing "Valid GitLab slugs"
    (is (true? (core/valid-repo-slug? "gitlab:group/project")))
    (is (true? (core/valid-repo-slug? "gitlab:org/sub/project"))))

  (testing "Invalid slugs"
    (is (false? (core/valid-repo-slug? "")))
    (is (false? (core/valid-repo-slug? "noslash")))
    (is (false? (core/valid-repo-slug? "/leading-slash")))
    (is (false? (core/valid-repo-slug? "https://github.com/owner/repo")))
    (is (false? (core/valid-repo-slug? "/absolute/path/to/repo"))))

  (testing "Slugs with special chars at boundaries"
    (is (true? (core/valid-repo-slug? "a/b")))
    (is (false? (core/valid-repo-slug? "a/")))))

(deftest repo-provider-test
  (testing "GitHub repos return :github"
    (is (= :github (core/repo-provider "owner/repo"))))

  (testing "GitLab repos return :gitlab"
    (is (= :gitlab (core/repo-provider "gitlab:group/project"))))

  (testing "nil input returns :github (default)"
    (is (= :github (core/repo-provider nil))))

  (testing "Empty string returns :github"
    (is (= :github (core/repo-provider "")))))

(deftest provider-repo-slug-test
  (testing "GitHub slug returned as-is"
    (is (= "owner/repo" (core/provider-repo-slug "owner/repo"))))

  (testing "GitLab prefix stripped"
    (is (= "group/project" (core/provider-repo-slug "gitlab:group/project"))))

  (testing "GitLab subgroups stripped correctly"
    (is (= "org/sub/project" (core/provider-repo-slug "gitlab:org/sub/project")))))

(deftest url-encode-test
  (testing "Encodes special characters"
    (is (= "hello+world" (core/url-encode "hello world")))
    (is (= "a%2Fb" (core/url-encode "a/b"))))

  (testing "Plain strings unchanged"
    (is (= "simple" (core/url-encode "simple")))))

(deftest succeeded-test
  (testing "Returns true for success maps"
    (is (true? (core/succeeded? {:success? true}))))

  (testing "Returns false for failure maps"
    (is (false? (core/succeeded? {:success? false}))))

  (testing "Returns false for missing key"
    (is (false? (core/succeeded? {}))))

  (testing "Returns false for nil"
    (is (false? (core/succeeded? nil)))))

(deftest result-success-test
  (testing "Creates success map with merged data"
    (let [r (core/result-success {:foo :bar})]
      (is (true? (:success? r)))
      (is (= :bar (:foo r))))))

(deftest result-failure-test
  (testing "Creates failure map with message"
    (let [r (core/result-failure "something broke")]
      (is (false? (:success? r)))
      (is (= "something broke" (:error r)))))

  (testing "Creates failure map with message and data"
    (let [r (core/result-failure "oops" {:repo "a/b"})]
      (is (false? (:success? r)))
      (is (= "oops" (:error r)))
      (is (= "a/b" (:repo r)))))

  (testing "Nil/blank message gets default"
    (let [r (core/result-failure nil)]
      (is (false? (:success? r)))
      (is (string? (:error r)))
      (is (pos? (count (:error r))))))

  (testing "Empty string message gets default"
    (let [r (core/result-failure "")]
      (is (false? (:success? r)))
      (is (pos? (count (:error r)))))))

(deftest gh-error-message-test
  (testing "Prefers err over out when err is non-blank"
    (is (= "auth failed" (core/gh-error-message "output" "auth failed"))))

  (testing "Falls back to out when err is blank"
    (is (= "output msg" (core/gh-error-message "output msg" ""))))

  (testing "Returns nil when both blank"
    (is (nil? (core/gh-error-message "" ""))))

  (testing "Handles nil inputs"
    (is (nil? (core/gh-error-message nil nil)))))

(deftest ex-msg-test
  (testing "Extracts exception message"
    (is (= "boom" (core/ex-msg (Exception. "boom")))))

  (testing "Falls back to class name when message is nil"
    (let [e (proxy [Exception] []
              (getMessage [] nil))]
      (is (string? (core/ex-msg e))))))

(deftest normalized-limit-test
  (testing "Uses provided integer limit when positive"
    (is (= 10 (core/normalized-limit 10 50))))

  (testing "Uses default when limit is zero"
    (is (= 50 (core/normalized-limit 0 50))))

  (testing "Uses default when limit is negative"
    (is (= 50 (core/normalized-limit -5 50))))

  (testing "Uses default when limit is nil"
    (is (= 50 (core/normalized-limit nil 50))))

  (testing "Uses default when limit is a string"
    (is (= 50 (core/normalized-limit "10" 50)))))

(deftest normalized-repos-test
  (testing "Extracts, normalizes, and filters valid repos"
    (let [cfg {:fleet {:repos ["Owner/Repo" "  acme/app  " "invalid" "gitlab:org/proj"]}}]
      (is (= ["owner/repo" "acme/app" "gitlab:org/proj"]
             (core/normalized-repos cfg)))))

  (testing "Returns empty vec for missing repos key"
    (is (= [] (core/normalized-repos {}))))

  (testing "Returns empty vec for empty repos"
    (is (= [] (core/normalized-repos {:fleet {:repos []}})))))

;; ============================================================================
;; Layer 1: Fleet config I/O
;; ============================================================================

(deftest load-fleet-config-test
  (testing "Loads valid config from file"
    (let [path (temp-config-path)]
      (spit path (pr-str {:fleet {:repos ["a/b" "c/d"]}}))
      (is (= {:fleet {:repos ["a/b" "c/d"]}}
             (core/load-fleet-config path)))))

  (testing "Returns default for non-existent file"
    (is (= {:fleet {:repos []}}
           (core/load-fleet-config "/tmp/does-not-exist-miniforge-42.edn"))))

  (testing "Returns default for corrupt file"
    (let [path (temp-config-path)]
      (spit path "NOT VALID EDN {{{{")
      (is (= {:fleet {:repos []}}
             (core/load-fleet-config path))))))

(deftest save-fleet-config-test
  (testing "Writes config and reads it back"
    (let [path (temp-config-path)
          cfg {:fleet {:repos ["x/y"]}}]
      (core/save-fleet-config! cfg path)
      (is (= cfg (core/load-fleet-config path)))))

  (testing "Creates parent directories"
    (let [dir (str (System/getProperty "java.io.tmpdir")
                   "/miniforge-test-" (System/currentTimeMillis))
          path (str dir "/config.edn")
          cfg {:fleet {:repos ["a/b"]}}]
      (core/save-fleet-config! cfg path)
      (is (= cfg (core/load-fleet-config path)))
      ;; cleanup
      (.delete (java.io.File. path))
      (.delete (java.io.File. dir)))))

(deftest get-configured-repos-test
  (testing "Returns normalized, validated, distinct repos"
    (let [path (temp-config-path)]
      (spit path (pr-str {:fleet {:repos ["Owner/Repo" "owner/repo" "invalid" "a/b"]}}))
      (is (= ["owner/repo" "a/b"]
             (core/get-configured-repos path)))))

  (testing "Returns empty for missing file"
    (is (= [] (core/get-configured-repos "/tmp/nonexistent-cfg-42.edn")))))

(deftest add-repo-test
  (testing "Adds new repo to empty config"
    (let [path (temp-config-path)]
      (spit path (pr-str {:fleet {:repos []}}))
      (let [result (core/add-repo! "acme/app" path)]
        (is (true? (:success? result)))
        (is (true? (:added? result)))
        (is (= "acme/app" (:repo result)))
        (is (some #{"acme/app"} (:repos result))))))

  (testing "Adding duplicate repo reports added? false"
    (let [path (temp-config-path)]
      (spit path (pr-str {:fleet {:repos ["acme/app"]}}))
      (let [result (core/add-repo! "acme/app" path)]
        (is (true? (:success? result)))
        (is (false? (:added? result))))))

  (testing "Adding duplicate with different case reports added? false"
    (let [path (temp-config-path)]
      (spit path (pr-str {:fleet {:repos ["acme/app"]}}))
      (let [result (core/add-repo! "Acme/App" path)]
        (is (true? (:success? result)))
        (is (false? (:added? result))))))

  (testing "Blank repo slug fails"
    (let [path (temp-config-path)]
      (spit path (pr-str {:fleet {:repos []}}))
      (let [result (core/add-repo! "  " path)]
        (is (false? (:success? result)))
        (is (string? (:error result))))))

  (testing "Invalid repo slug fails"
    (let [path (temp-config-path)]
      (spit path (pr-str {:fleet {:repos []}}))
      (let [result (core/add-repo! "/path/to/repo" path)]
        (is (false? (:success? result))))))

  (testing "Adds GitLab repo successfully"
    (let [path (temp-config-path)]
      (spit path (pr-str {:fleet {:repos []}}))
      (let [result (core/add-repo! "gitlab:mygroup/myproject" path)]
        (is (true? (:success? result)))
        (is (true? (:added? result)))
        (is (= "gitlab:mygroup/myproject" (:repo result)))))))

(deftest remove-repo-test
  (testing "Removes existing repo"
    (let [path (temp-config-path)]
      (spit path (pr-str {:fleet {:repos ["acme/app" "other/lib"]}}))
      (let [result (core/remove-repo! "acme/app" path)]
        (is (true? (:success? result)))
        (is (true? (:removed? result)))
        (is (= ["other/lib"] (:repos result))))))

  (testing "Removing non-existent repo reports removed? false"
    (let [path (temp-config-path)]
      (spit path (pr-str {:fleet {:repos ["acme/app"]}}))
      (let [result (core/remove-repo! "other/lib" path)]
        (is (true? (:success? result)))
        (is (false? (:removed? result))))))

  (testing "Blank slug fails"
    (let [path (temp-config-path)]
      (spit path (pr-str {:fleet {:repos ["acme/app"]}}))
      (let [result (core/remove-repo! "  " path)]
        (is (false? (:success? result)))))))

;; ============================================================================
;; Layer 2: Provider CLI interaction (mocked)
;; ============================================================================

(def sample-github-pr-json
  "[{\"number\":42,\"title\":\"Add feature\",\"url\":\"https://github.com/acme/app/pull/42\",\"state\":\"OPEN\",\"headRefName\":\"feat/new\",\"isDraft\":false,\"reviewDecision\":\"APPROVED\",\"statusCheckRollup\":[{\"conclusion\":\"SUCCESS\"}],\"mergeStateStatus\":\"CLEAN\",\"additions\":10,\"deletions\":5,\"changedFiles\":3,\"author\":{\"login\":\"dev\"}}]")

(def sample-closed-pr-json
  "[{\"number\":99,\"title\":\"Old PR\",\"url\":\"https://github.com/acme/app/pull/99\",\"state\":\"CLOSED\",\"headRefName\":\"fix/old\",\"isDraft\":false,\"reviewDecision\":null,\"statusCheckRollup\":[]}]")

(deftest run-gh-test
  (testing "Successful gh command returns success map"
    (with-redefs [clojure.java.shell/sh
                  (fn [& _args]
                    {:exit 0 :out "hello" :err ""})]
      (let [r (core/run-gh "version")]
        (is (true? (:success? r)))
        (is (= "hello" (:out r))))))

  (testing "Failed gh command returns failure map"
    (with-redefs [clojure.java.shell/sh
                  (fn [& _args]
                    {:exit 1 :out "" :err "not found"})]
      (let [r (core/run-gh "version")]
        (is (false? (:success? r)))
        (is (= "not found" (:err r))))))

  (testing "Exception in sh call is caught"
    (with-redefs [clojure.java.shell/sh
                  (fn [& _args]
                    (throw (Exception. "gh not installed")))]
      (let [r (core/run-gh "version")]
        (is (false? (:success? r)))
        (is (= "gh not installed" (:err r)))))))

(deftest run-glab-test
  (testing "Successful glab command returns success map"
    (with-redefs [clojure.java.shell/sh
                  (fn [& _args]
                    {:exit 0 :out "ok" :err ""})]
      (let [r (core/run-glab "version")]
        (is (true? (:success? r))))))

  (testing "Exception in glab call is caught"
    (with-redefs [clojure.java.shell/sh
                  (fn [& _args]
                    (throw (Exception. "glab not installed")))]
      (let [r (core/run-glab "version")]
        (is (false? (:success? r)))))))

(deftest fetch-github-prs-test
  (testing "Successful fetch returns sorted PRs with repo and provider"
    (with-redefs [clojure.java.shell/sh
                  (fn [& _args]
                    {:exit 0 :out sample-github-pr-json :err ""})]
      (let [result (core/fetch-github-prs "acme/app" :open)]
        (is (true? (:success? result)))
        (is (= "acme/app" (:repo result)))
        (is (= :github (:provider result)))
        (is (= 1 (count (:prs result))))
        (is (= 42 (:pr/number (first (:prs result))))))))

  (testing "CLI failure returns failure result"
    (with-redefs [clojure.java.shell/sh
                  (fn [& _args]
                    {:exit 1 :out "" :err "HTTP 401 Bad credentials"})]
      (let [result (core/fetch-github-prs "acme/app" :open)]
        (is (false? (:success? result)))
        (is (string? (:error result))))))

  (testing "Malformed JSON returns failure result"
    (with-redefs [clojure.java.shell/sh
                  (fn [& _args]
                    {:exit 0 :out "NOT JSON" :err ""})]
      (let [result (core/fetch-github-prs "acme/app" :open)]
        (is (false? (:success? result)))))))

(deftest fetch-open-github-prs-test
  (testing "Filters out closed/merged PRs from open fetch"
    (with-redefs [clojure.java.shell/sh
                  (fn [& _args]
                    {:exit 0 :out sample-closed-pr-json :err ""})]
      (let [result (core/fetch-open-github-prs "acme/app")]
        (is (true? (:success? result)))
        ;; Closed PR filtered out
        (is (empty? (:prs result)))))))

(deftest fetch-open-prs-dispatches-by-provider-test
  (testing "GitHub repo dispatches to GitHub fetch"
    (with-redefs [clojure.java.shell/sh
                  (fn [& args]
                    (if (= "gh" (first args))
                      {:exit 0 :out "[]" :err ""}
                      {:exit 1 :out "" :err "wrong binary"}))]  
      (let [result (core/fetch-open-prs "acme/app")]
        (is (true? (:success? result))))))

  (testing "GitLab repo dispatches to GitLab fetch"
    (with-redefs [clojure.java.shell/sh
                  (fn [& args]
                    (if (= "glab" (first args))
                      {:exit 0 :out "[]" :err ""}
                      {:exit 1 :out "" :err "wrong binary"}))]  
      (let [result (core/fetch-open-prs "gitlab:org/proj")]
        (is (true? (:success? result)))))))

(deftest fetch-prs-by-state-test
  (testing "State parameter maps correctly for GitHub"
    (let [captured-args (atom nil)]
      (with-redefs [clojure.java.shell/sh
                    (fn [& args]
                      (reset! captured-args (vec args))
                      {:exit 0 :out "[]" :err ""})]
        (core/fetch-github-prs "acme/app" :merged)
        (is (some #{"merged"} @captured-args)))))

  (testing "Default state is open"
    (let [captured-args (atom nil)]
      (with-redefs [clojure.java.shell/sh
                    (fn [& args]
                      (reset! captured-args (vec args))
                      {:exit 0 :out "[]" :err ""})]
        (core/fetch-github-prs "acme/app" :open)
        (is (some #{"open"} @captured-args))))))

(deftest gitlab-state-param-test
  (testing "Maps canonical states to GitLab API values"
    (is (= "opened" (core/gitlab-state-param :open)))
    (is (= "closed" (core/gitlab-state-param :closed)))
    (is (= "merged" (core/gitlab-state-param :merged)))
    (is (= "all"    (core/gitlab-state-param :all))))

  (testing "Unknown state defaults to opened"
    (is (= "opened" (core/gitlab-state-param :unknown)))))

;; ============================================================================
;; Layer 2b: GitLab MR enrichment (mocked)
;; ============================================================================

(deftest fetch-gitlab-diff-stats-batch-test
  (testing "Returns empty map for empty iids"
    (is (= nil (core/fetch-gitlab-diff-stats-batch "group/proj" []))))

  (testing "Returns empty map on CLI failure"
    (with-redefs [clojure.java.shell/sh
                  (fn [& _args]
                    {:exit 1 :out "" :err "fail"})]
      (is (= {} (core/fetch-gitlab-diff-stats-batch "group/proj" ["1"])))))

  (testing "Returns stats on success"
    (let [graphql-response "{\"data\":{\"project\":{\"mr1\":{\"diffStatsSummary\":{\"additions\":10,\"deletions\":5,\"fileCount\":3}}}}}"]
      (with-redefs [clojure.java.shell/sh
                    (fn [& _args]
                      {:exit 0 :out graphql-response :err ""})]
        (let [stats (core/fetch-gitlab-diff-stats-batch "group/proj" ["1"])]
          (is (= 10 (:additions (get stats "1"))))
          (is (= 5 (:deletions (get stats "1"))))
          (is (= 3 (:file-count (get stats "1")))))))))

(deftest enrich-gitlab-mrs-with-diff-stats-test
  (testing "Skips enrichment when no open MRs"
    (let [mrs [{:iid "1" :state "closed"}
               {:iid "2" :state "merged"}]]
      (is (= mrs (core/enrich-gitlab-mrs-with-diff-stats "g/p" mrs)))))

  (testing "Enriches open MRs with diff stats"
    (let [graphql-response "{\"data\":{\"project\":{\"mr10\":{\"diffStatsSummary\":{\"additions\":20,\"deletions\":3,\"fileCount\":2}}}}}"
          mrs [{:iid "10" :state "opened"}
               {:iid "20" :state "closed"}]]
      (with-redefs [clojure.java.shell/sh
                    (fn [& _args]
                      {:exit 0 :out graphql-response :err ""})]
        (let [enriched (core/enrich-gitlab-mrs-with-diff-stats "g/p" mrs)]
          ;; Open MR enriched
          (is (= 20 (:additions (first enriched))))
          (is (= 3 (:deletions (first enriched))))
          ;; Closed MR unchanged
          (is (nil? (:additions (second enriched)))))))))

;; ============================================================================
;; Layer 2c: fetch-all-fleet-prs
;; ============================================================================

(deftest fetch-all-fleet-prs-test
  (testing "Returns flat vector of PRs from all repos"
    (let [path (temp-config-path)]
      (spit path (pr-str {:fleet {:repos ["acme/app"]}}))
      (with-redefs [clojure.java.shell/sh
                    (make-sh-router
                     {"acme/app" {:exit 0 :out sample-github-pr-json :err ""}})]
        (let [prs (core/fetch-all-fleet-prs {:config-path path})]
          (is (vector? prs))
          (is (= 1 (count prs)))
          (is (= 42 (:pr/number (first prs))))))))

  (testing "Returns empty for empty fleet"
    (let [path (temp-config-path)]
      (spit path (pr-str {:fleet {:repos []}}))
      (is (= [] (core/fetch-all-fleet-prs {:config-path path})))))

  (testing "Filters out failed repos"
    (let [path (temp-config-path)]
      (spit path (pr-str {:fleet {:repos ["acme/app" "bad/repo"]}}))
      (with-redefs [clojure.java.shell/sh
                    (make-sh-router
                     {"acme/app" {:exit 0 :out sample-github-pr-json :err ""}
                      "bad/repo" {:exit 1 :out "" :err "fail"}})]
        (let [prs (core/fetch-all-fleet-prs {:config-path path})]
          (is (= 1 (count prs))))))))

;; ============================================================================
;; Layer 3: Error classification (supplementary to error_classification_test)
;; ============================================================================

(deftest classify-sync-error-priority-test
  (testing "First matching pattern wins (401 before rate_limit)"
    (let [result (core/classify-sync-error "401 rate limit")]
      (is (= :auth-failure (:error-category result))))))

(deftest fetch-repo-with-status-gitlab-test
  (testing "GitLab repo errors are classified"
    (with-redefs [clojure.java.shell/sh
                  (fn [& _args]
                    {:exit 1 :out "" :err "HTTP 404 Not Found"})]
      (let [status (core/fetch-repo-with-status "gitlab:group/proj")]
        (is (= :error (:status status)))
        (is (= :not-found (:error-category status)))
        (is (= "gitlab:group/proj" (:repo status)))))))

;; ============================================================================
;; Layer 4: Discovery
;; ============================================================================

(deftest discover-repos-test
  (testing "Discovers repos from org and adds to config"
    (let [path (temp-config-path)
          api-response "[{\"full_name\":\"myorg/repo1\"},{\"full_name\":\"myorg/repo2\"}]"]
      (spit path (pr-str {:fleet {:repos []}}))
      (with-redefs [clojure.java.shell/sh
                    (fn [& _args]
                      {:exit 0 :out api-response :err ""})]
        (let [result (core/discover-repos! {:owner "myorg" :config-path path})]
          (is (true? (:success? result)))
          (is (= 2 (:discovered result)))
          (is (= 2 (:added result)))
          ;; Repos persisted
          (is (= ["myorg/repo1" "myorg/repo2"]
                 (core/get-configured-repos path)))))))

  (testing "Skips already-configured repos"
    (let [path (temp-config-path)
          api-response "[{\"full_name\":\"myorg/repo1\"},{\"full_name\":\"myorg/repo2\"}]"]
      (spit path (pr-str {:fleet {:repos ["myorg/repo1"]}}))
      (with-redefs [clojure.java.shell/sh
                    (fn [& _args]
                      {:exit 0 :out api-response :err ""})]
        (let [result (core/discover-repos! {:owner "myorg" :config-path path})]
          (is (true? (:success? result)))
          (is (= 2 (:discovered result)))
          (is (= 1 (:added result)))))))

  (testing "CLI failure returns failure result"
    (let [path (temp-config-path)]
      (spit path (pr-str {:fleet {:repos []}}))
      (with-redefs [clojure.java.shell/sh
                    (fn [& _args]
                      {:exit 1 :out "" :err "auth required"})]
        (let [result (core/discover-repos! {:owner "myorg" :config-path path})]
          (is (false? (:success? result)))))))

  (testing "Respects limit parameter"
    (let [path (temp-config-path)
          api-response (str "[" (str/join ","
                                  (map #(str "{\"full_name\":\"org/r" % "\"}") (range 10))) "]")]
      (spit path (pr-str {:fleet {:repos []}}))
      (with-redefs [clojure.java.shell/sh
                    (fn [& _args]
                      {:exit 0 :out api-response :err ""})]
        (let [result (core/discover-repos! {:owner "org" :limit 3 :config-path path})]
          (is (true? (:success? result)))
          (is (= 3 (:discovered result))))))))

;; ============================================================================
;; Layer 4b: Viewer repos helpers
;; ============================================================================

(deftest viewer-repos-gh-args-test
  (testing "First page has no after cursor"
    (let [args (core/viewer-repos-gh-args 50 [] nil)]
      (is (some #{"api"} args))
      (is (some #{"graphql"} args))
      (is (not (some #(and (string? %) (.startsWith ^String % "after=")) args)))))

  (testing "Subsequent page includes after cursor"
    (let [args (core/viewer-repos-gh-args 50 [] "cursor123")]
      (is (some #(= "after=cursor123" %) args)))))

(deftest parse-viewer-repos-page-test
  (testing "Extracts repos and pagination from GraphQL response"
    (let [parsed {:data {:viewer {:repositories
                                   {:nodes [{:nameWithOwner "org/repo1"}
                                            {:nameWithOwner "org/repo2"}]
                                    :pageInfo {:hasNextPage true
                                               :endCursor "abc123"}}}}}
          result (core/parse-viewer-repos-page parsed)]
      (is (= ["org/repo1" "org/repo2"] (:repos result)))
      (is (true? (:has-next? result)))
      (is (= "abc123" (:cursor result)))))

  (testing "Handles empty response"
    (let [result (core/parse-viewer-repos-page {:data {:viewer {:repositories {:nodes [] :pageInfo {}}}}})] 
      (is (empty? (:repos result)))
      (is (false? (:has-next? result))))))

(deftest parse-gh-full-name-repos-test
  (testing "Parses, normalizes, and limits repos from JSON"
    (let [json "[{\"full_name\":\"Org/Repo1\"},{\"full_name\":\"Org/Repo2\"},{\"full_name\":\"Org/Repo3\"}]"]
      (is (= ["org/repo1" "org/repo2"] (core/parse-gh-full-name-repos json 2))))))

(deftest parse-glab-project-repos-test
  (testing "Parses GitLab projects with gitlab: prefix"
    (let [json "[{\"path_with_namespace\":\"group/project1\"},{\"path_with_namespace\":\"group/sub/project2\"}]"]
      (is (= ["gitlab:group/project1" "gitlab:group/sub/project2"]
             (core/parse-glab-project-repos json 100))))))

;; ============================================================================
;; Layer 4c: list-org-repos dispatch
;; ============================================================================

(deftest list-org-repos-unknown-provider-test
  (testing "Unknown provider returns failure"
    (let [result (core/list-org-repos {:provider :bitbucket :owner "org"})]
      (is (false? (:success? result)))
      (is (.contains ^String (:error result) "Unknown provider")))))

(deftest list-org-repos-github-owner-test
  (testing "GitHub with owner fetches org repos"
    (let [api-response "[{\"full_name\":\"myorg/repo1\"}]"]
      (with-redefs [clojure.java.shell/sh
                    (fn [& args]
                      (if (some #(and (string? %) (.contains ^String % "orgs/myorg")) args)
                        {:exit 0 :out api-response :err ""}
                        {:exit 1 :out "" :err ""}))] 
        (let [result (core/list-org-repos {:provider :github :owner "myorg" :limit 50})]
          (is (true? (:success? result)))
          (is (= ["myorg/repo1"] (:repos result))))))))

(deftest list-gitlab-repos-test
  (testing "GitLab repos fetched with correct endpoint"
    (let [api-response "[{\"path_with_namespace\":\"grp/proj\"}]"]
      (with-redefs [clojure.java.shell/sh
                    (fn [& _args]
                      {:exit 0 :out api-response :err ""})]
        (let [result (core/list-gitlab-repos {:owner "grp" :limit 50})]
          (is (true? (:success? result)))
          (is (= :gitlab (:provider result)))
          (is (= ["gitlab:grp/proj"] (:repos result))))))))

(deftest list-org-repos-all-provider-test
  (testing ":all provider combines GitHub and GitLab results"
    (with-redefs [clojure.java.shell/sh
                  (fn [& args]
                    (cond
                      (= "gh" (first args))
                      {:exit 0 :out "[{\"full_name\":\"org/gh-repo\"}]" :err ""}

                      (= "glab" (first args))
                      {:exit 0 :out "[{\"path_with_namespace\":\"org/gl-repo\"}]" :err ""}

                      :else
                      {:exit 1 :out "" :err "unknown"}))]
      (let [result (core/list-org-repos {:provider :all :owner "org" :limit 50})]
        (is (true? (:success? result)))
        (is (some #{"org/gh-repo"} (:repos result)))
        (is (some #{"gitlab:org/gl-repo"} (:repos result)))))))

(deftest list-github-viewer-orgs-test
  (testing "Returns org names on success"
    (with-redefs [clojure.java.shell/sh
                  (fn [& _args]
                    {:exit 0 :out "[{\"login\":\"MyOrg\"},{\"login\":\"Other\"}]" :err ""})]
      (let [result (core/list-github-viewer-orgs)]
        (is (true? (:success? result)))
        (is (= ["myorg" "other"] (:orgs result))))))

  (testing "Returns failure on CLI error"
    (with-redefs [clojure.java.shell/sh
                  (fn [& _args]
                    {:exit 1 :out "" :err "unauthorized"})]
      (let [result (core/list-github-viewer-orgs)]
        (is (false? (:success? result)))))))
