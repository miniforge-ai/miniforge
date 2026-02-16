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
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.java.shell]
   [ai.miniforge.pr-sync.core :as core]))

;; ─────────────────────────────────────────────────────────────────────────────
;; Test helpers

(defn- temp-config-path []
  (let [f (java.io.File/createTempFile "miniforge-test-config" ".edn")]
    (.deleteOnExit f)
    (.getAbsolutePath f)))

;; ─────────────────────────────────────────────────────────────────────────────
;; Fleet config tests

(deftest load-fleet-config-test
  (testing "Missing file returns default config"
    (let [cfg (core/load-fleet-config "/tmp/nonexistent-miniforge-config-12345.edn")]
      (is (= {:fleet {:repos []}} cfg))))

  (testing "Existing file is read"
    (let [path (temp-config-path)]
      (spit path (pr-str {:fleet {:repos ["owner/repo"]}}))
      (let [cfg (core/load-fleet-config path)]
        (is (= ["owner/repo"] (get-in cfg [:fleet :repos])))))))

(deftest get-configured-repos-test
  (testing "Returns repos from config file"
    (let [path (temp-config-path)]
      (spit path (pr-str {:fleet {:repos ["Foo/Bar" "baz/qux"]}}))
      (let [repos (core/get-configured-repos path)]
        (is (= ["foo/bar" "baz/qux"] repos)))))

  (testing "Filters invalid slugs"
    (let [path (temp-config-path)]
      (spit path (pr-str {:fleet {:repos ["valid/repo" "" "no-slash"]}}))
      (let [repos (core/get-configured-repos path)]
        (is (= ["valid/repo"] repos)))))

  (testing "Empty config returns empty vec"
    (let [path (temp-config-path)]
      (spit path (pr-str {}))
      (let [repos (core/get-configured-repos path)]
        (is (= [] repos)))))

  (testing "Keeps valid gitlab-prefixed slugs"
    (let [path (temp-config-path)]
      (spit path (pr-str {:fleet {:repos ["gitlab:Group/Sub/Repo"]}}))
      (let [repos (core/get-configured-repos path)]
        (is (= ["gitlab:group/sub/repo"] repos))))))

(deftest add-repo-test
  (testing "Adds valid repo to empty config"
    (let [path (temp-config-path)]
      (spit path (pr-str {:fleet {:repos []}}))
      (let [result (core/add-repo! "owner/name" path)]
        (is (:success? result))
        (is (:added? result))
        (is (= "owner/name" (:repo result)))
        (is (some #{"owner/name"} (:repos result))))))

  (testing "Normalizes to lowercase"
    (let [path (temp-config-path)]
      (spit path (pr-str {:fleet {:repos []}}))
      (let [result (core/add-repo! "Owner/Name" path)]
        (is (= "owner/name" (:repo result))))))

  (testing "Does not duplicate existing repo"
    (let [path (temp-config-path)]
      (spit path (pr-str {:fleet {:repos ["owner/name"]}}))
      (let [result (core/add-repo! "owner/name" path)]
        (is (:success? result))
        (is (not (:added? result))))))

  (testing "Rejects blank repo"
    (let [path (temp-config-path)]
      (spit path (pr-str {:fleet {:repos []}}))
      (let [result (core/add-repo! "" path)]
        (is (not (:success? result))))))

  (testing "Rejects invalid format"
    (let [path (temp-config-path)]
      (spit path (pr-str {:fleet {:repos []}}))
      (let [result (core/add-repo! "no-slash" path)]
        (is (not (:success? result))))))

  (testing "Accepts gitlab repository format"
    (let [path (temp-config-path)]
      (spit path (pr-str {:fleet {:repos []}}))
      (let [result (core/add-repo! "gitlab:Group/Sub/Repo" path)]
        (is (:success? result))
        (is (:added? result))
        (is (= "gitlab:group/sub/repo" (:repo result)))))))

(deftest remove-repo-test
  (testing "Removes existing repo"
    (let [path (temp-config-path)]
      (spit path (pr-str {:fleet {:repos ["owner/name" "other/repo"]}}))
      (let [result (core/remove-repo! "owner/name" path)]
        (is (:success? result))
        (is (:removed? result))
        (is (not (some #{"owner/name"} (:repos result))))
        (is (some #{"other/repo"} (:repos result))))))

  (testing "Handles non-existent repo gracefully"
    (let [path (temp-config-path)]
      (spit path (pr-str {:fleet {:repos ["owner/name"]}}))
      (let [result (core/remove-repo! "not/here" path)]
        (is (:success? result))
        (is (not (:removed? result)))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; PR status mapping tests

(deftest pr-status-from-provider-test
  (testing "Open non-draft PR"
    (is (= :open (core/pr-status-from-provider
                   {:state "OPEN" :isDraft false :reviewDecision nil}))))

  (testing "Draft PR"
    (is (= :draft (core/pr-status-from-provider
                    {:state "OPEN" :isDraft true :reviewDecision nil}))))

  (testing "Approved PR"
    (is (= :approved (core/pr-status-from-provider
                       {:state "OPEN" :isDraft false :reviewDecision "APPROVED"}))))

  (testing "Changes requested"
    (is (= :changes-requested (core/pr-status-from-provider
                                {:state "OPEN" :isDraft false :reviewDecision "CHANGES_REQUESTED"}))))

  (testing "Review required"
    (is (= :reviewing (core/pr-status-from-provider
                        {:state "OPEN" :isDraft false :reviewDecision "REVIEW_REQUIRED"}))))

  (testing "Closed/merged PR"
    (is (= :closed (core/pr-status-from-provider
                     {:state "MERGED" :isDraft false :reviewDecision nil})))
    (is (= :closed (core/pr-status-from-provider
                     {:state "CLOSED" :isDraft false :reviewDecision nil})))))

(deftest check-rollup->ci-status-test
  (testing "All passed"
    (is (= :passed (core/check-rollup->ci-status
                     [{:conclusion "SUCCESS"} {:conclusion "SUCCESS"}]))))

  (testing "Any failed"
    (is (= :failed (core/check-rollup->ci-status
                     [{:conclusion "SUCCESS"} {:conclusion "FAILURE"}]))))

  (testing "Running checks"
    (is (= :running (core/check-rollup->ci-status
                      [{:conclusion "SUCCESS"} {:status "IN_PROGRESS"}]))))

  (testing "Nil rollup"
    (is (= :pending (core/check-rollup->ci-status nil))))

  (testing "Empty entries"
    (is (= :pending (core/check-rollup->ci-status [])))))

(deftest provider-pr->train-pr-test
  (testing "Converts provider PR to train PR shape"
    (let [pr {:number 42
              :title "Fix auth"
              :url "https://github.com/owner/repo/pull/42"
              :headRefName "fix-auth"
              :state "OPEN"
              :isDraft false
              :reviewDecision "APPROVED"
              :statusCheckRollup [{:conclusion "SUCCESS"}]}
          result (core/provider-pr->train-pr pr "owner/repo")]
      (is (= 42 (:pr/number result)))
      (is (= "Fix auth" (:pr/title result)))
      (is (= "https://github.com/owner/repo/pull/42" (:pr/url result)))
      (is (= "fix-auth" (:pr/branch result)))
      (is (= :approved (:pr/status result)))
      (is (= :passed (:pr/ci-status result)))
      (is (= "owner/repo" (:pr/repo result)))))

  (testing "Without repo arg, :pr/repo is absent"
    (let [result (core/provider-pr->train-pr {:number 1 :title "X" :state "OPEN"})]
      (is (nil? (:pr/repo result))))))

(deftest fetch-all-fleet-prs-multi-provider-test
  (testing "Fetches open items from both GitHub and GitLab repositories"
    (let [path (temp-config-path)]
      (spit path (pr-str {:fleet {:repos ["owner/repo" "gitlab:team/service"]}}))
      (with-redefs [clojure.java.shell/sh
                    (fn [& args]
                      (cond
                        (and (= "gh" (first args))
                             (some #{"pr"} args)
                             (some #{"list"} args))
                        {:exit 0
                         :out "[{\"number\":12,\"title\":\"Fix GH\",\"url\":\"https://github.com/owner/repo/pull/12\",\"state\":\"OPEN\",\"headRefName\":\"fix-gh\",\"isDraft\":false,\"reviewDecision\":null,\"statusCheckRollup\":[{\"conclusion\":\"SUCCESS\"}]}]"
                         :err ""}

                        (and (= "glab" (first args))
                             (some #(and (string? %) (.contains ^String % "/merge_requests")) args))
                        {:exit 0
                         :out "[{\"iid\":34,\"title\":\"Fix GL\",\"web_url\":\"https://gitlab.com/team/service/-/merge_requests/34\",\"source_branch\":\"fix-gl\",\"state\":\"opened\",\"draft\":false,\"merge_status\":\"can_be_merged\",\"head_pipeline\":{\"status\":\"success\"}}]"
                         :err ""}

                        :else
                        {:exit 1 :out "" :err (str "unexpected call: " args)}))]
        (let [prs (core/fetch-all-fleet-prs {:config-path path})]
          (is (= 2 (count prs)))
          (is (some #(= "owner/repo" (:pr/repo %)) prs))
          (is (some #(= "gitlab:team/service" (:pr/repo %)) prs))
          (is (some #(= :merge-ready (:pr/status %)) prs)))))))

(deftest list-org-repos-test
  (testing "Without owner, lists viewer-accessible repos plus org repositories"
    (with-redefs [clojure.java.shell/sh
                  (fn [& args]
                    (cond
                      (and (= "gh" (first args))
                           (some #{"graphql"} args))
                      {:exit 0
                       :out "{\"data\":{\"viewer\":{\"repositories\":{\"nodes\":[{\"nameWithOwner\":\"Org/RepoA\"},{\"nameWithOwner\":\"me/repo-b\"}],\"pageInfo\":{\"hasNextPage\":false,\"endCursor\":null}}}}}"
                       :err ""}

                      (and (= "gh" (first args))
                           (some #(and (string? %) (.contains ^String % "user/orgs")) args))
                      {:exit 0
                       :out "[{\"login\":\"kiddom\"}]"
                       :err ""}

                      (and (= "gh" (first args))
                           (some #(and (string? %) (.contains ^String % "orgs/kiddom/repos")) args))
                      {:exit 0
                       :out "[{\"full_name\":\"kiddom/platform\"}]"
                       :err ""}

                      :else
                      {:exit 1 :out "" :err "unexpected call"}))]
      (let [result (core/list-org-repos {})]
        (is (:success? result))
        (is (= ["org/repoa" "me/repo-b" "kiddom/platform"] (:repos result))))))

  (testing "Falls back to user/repos when GraphQL listing fails"
    (with-redefs [clojure.java.shell/sh
                  (fn [& args]
                    (cond
                      (and (= "gh" (first args))
                           (some #{"graphql"} args))
                      {:exit 1 :out "" :err "forbidden"}

                      (and (= "gh" (first args))
                           (some #(and (string? %) (.contains ^String % "user/orgs")) args))
                      {:exit 1 :out "" :err "sso required"}

                      (and (= "gh" (first args))
                           (some #(and (string? %) (.contains ^String % "user/repos")) args))
                      {:exit 0
                       :out "[{\"full_name\":\"fallback/repo1\"},{\"full_name\":\"fallback/repo2\"}]"
                       :err ""}

                      :else
                      {:exit 1 :out "" :err "unexpected call"}))]
      (let [result (core/list-org-repos {:limit 10})]
        (is (:success? result))
        (is (= ["fallback/repo1" "fallback/repo2"] (:repos result))))))

  (testing "Nil limit is normalized and does not throw"
    (with-redefs [clojure.java.shell/sh
                  (fn [& args]
                    (cond
                      (and (= "gh" (first args))
                           (some #{"graphql"} args))
                      {:exit 0
                       :out "{\"data\":{\"viewer\":{\"repositories\":{\"nodes\":[{\"nameWithOwner\":\"Org/RepoA\"}],\"pageInfo\":{\"hasNextPage\":false,\"endCursor\":null}}}}}"
                       :err ""}

                      (and (= "gh" (first args))
                           (some #(and (string? %) (.contains ^String % "user/orgs")) args))
                      {:exit 0 :out "[]" :err ""}

                      :else
                      {:exit 1 :out "" :err "unexpected call"}))]
      (let [result (core/list-org-repos {:limit nil})]
        (is (:success? result))
        (is (= ["org/repoa"] (:repos result))))))

  (testing "GitLab provider returns prefixed repositories"
    (with-redefs [clojure.java.shell/sh
                  (fn [& args]
                    (cond
                      (and (= "glab" (first args))
                           (some #(and (string? %) (.contains ^String % "projects?membership=true")) args))
                      {:exit 0
                       :out "[{\"path_with_namespace\":\"Platform/API\"},{\"path_with_namespace\":\"Data/ETL\"}]"
                       :err ""}
                      :else
                      {:exit 1 :out "" :err "unexpected call"}))]
      (let [result (core/list-org-repos {:provider :gitlab})]
        (is (:success? result))
        (is (= ["gitlab:platform/api" "gitlab:data/etl"] (:repos result))))))

  (testing "Provider :all merges GitHub and GitLab repositories"
    (with-redefs [clojure.java.shell/sh
                  (fn [& args]
                    (cond
                      (and (= "gh" (first args))
                           (some #{"graphql"} args))
                      {:exit 0
                       :out "{\"data\":{\"viewer\":{\"repositories\":{\"nodes\":[{\"nameWithOwner\":\"Org/RepoA\"}],\"pageInfo\":{\"hasNextPage\":false,\"endCursor\":null}}}}}"
                       :err ""}

                      (and (= "gh" (first args))
                           (some #(and (string? %) (.contains ^String % "user/orgs")) args))
                      {:exit 0 :out "[]" :err ""}

                      (and (= "glab" (first args))
                           (some #(and (string? %) (.contains ^String % "projects?membership=true")) args))
                      {:exit 0 :out "[{\"path_with_namespace\":\"Platform/API\"}]" :err ""}

                      :else
                      {:exit 1 :out "" :err "unexpected call"}))]
      (let [result (core/list-org-repos {:provider :all})]
        (is (:success? result))
        (is (= ["org/repoa" "gitlab:platform/api"] (:repos result)))))))
