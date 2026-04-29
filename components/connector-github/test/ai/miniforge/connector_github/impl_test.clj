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

(ns ai.miniforge.connector-github.impl-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.connector-github.impl :as impl]
            [ai.miniforge.connector-github.resources :as resources]
            [ai.miniforge.connector-http.rate-limit :as rate]
            [ai.miniforge.connector-http.etag :as etag]
            [ai.miniforge.schema.interface :as schema]))

(deftest resource-schemas-test
  (testing "resource-schemas returns all known resources"
    (let [schemas (resources/resource-schemas)]
      (is (= 6 (count schemas)))
      (is (every? :schema/name schemas))
      (is (every? :schema/endpoint schemas))
      (is (every? :schema/type schemas))
      (is (some #(= "repos" (:schema/name %)) schemas))
      (is (some #(= "issues" (:schema/name %)) schemas))
      (is (some #(= "pulls" (:schema/name %)) schemas))
      (is (some #(= "commits" (:schema/name %)) schemas))
      (is (some #(= "comments" (:schema/name %)) schemas))
      (is (some #(= "reviews" (:schema/name %)) schemas)))))

(deftest build-url-test
  (testing "builds org repos URL"
    (let [resource-def (resources/get-resource :repos)
          config       {:github/org "myorg"}
          url          (resources/build-url "https://api.github.com" resource-def config)]
      (is (= "https://api.github.com/orgs/myorg/repos" url))))

  (testing "builds user repos URL when only owner is provided"
    (let [resource-def (resources/get-resource :repos)
          config       {:github/owner "myuser"}
          url          (resources/build-url "https://api.github.com" resource-def config)]
      (is (= "https://api.github.com/users/myuser/repos" url))))

  (testing "builds issues URL with owner and repo"
    (let [resource-def (resources/get-resource :issues)
          config       {:github/owner "myuser" :github/repo "myrepo"}
          url          (resources/build-url "https://api.github.com" resource-def config)]
      (is (= "https://api.github.com/repos/myuser/myrepo/issues" url))))

  (testing "builds commits URL"
    (let [resource-def (resources/get-resource :commits)
          config       {:github/owner "myuser" :github/repo "myrepo"}
          url          (resources/build-url "https://api.github.com" resource-def config)]
      (is (= "https://api.github.com/repos/myuser/myrepo/commits" url))))

  (testing "builds reviews URL with pull number"
    (let [resource-def (resources/get-resource :reviews)
          config       {:github/owner "myuser" :github/repo "myrepo" :github/pull-number 42}
          url          (resources/build-url "https://api.github.com" resource-def config)]
      (is (= "https://api.github.com/repos/myuser/myrepo/pulls/42/reviews" url)))))

(deftest build-query-params-test
  (testing "includes default params and per_page"
    (let [resource-def (resources/get-resource :issues)
          params       (resources/build-query-params resource-def nil {})]
      (is (= 100 (get params "per_page")))
      (is (= "all" (get params :state)))
      (is (= "updated" (get params "sort")))
      (is (= "desc" (get params "direction")))))

  (testing "includes cursor value for incremental"
    (let [resource-def (resources/get-resource :issues)
          cursor       {:cursor/type :timestamp-watermark :cursor/value "2026-01-01T00:00:00Z"}
          params       (resources/build-query-params resource-def cursor {})]
      (is (= "2026-01-01T00:00:00Z" (get params "since")))))

  (testing "respects batch-size override"
    (let [resource-def (resources/get-resource :repos)
          params       (resources/build-query-params resource-def nil {:extract/batch-size 50})]
      (is (= 50 (get params "per_page")))))

  (testing "omits incremental param when no cursor"
    (let [resource-def (resources/get-resource :commits)
          params       (resources/build-query-params resource-def nil {})]
      (is (nil? (get params "since"))))))

(deftest connect-validates-config-test
  (testing "do-connect requires org or owner"
    (is (thrown? Exception (impl/do-connect {} nil)))))

(deftest connect-close-lifecycle-test
  (testing "connect and close work with org"
    (let [config {:github/org "test-org"}
          result (impl/do-connect config nil)]
      (is (string? (:connection/handle result)))
      (is (= :connected (:connector/status result)))
      (let [close-result (impl/do-close (:connection/handle result))]
        (is (= :closed (:connector/status close-result))))))

  (testing "connect and close work with owner"
    (let [config {:github/owner "test-user"}
          result (impl/do-connect config nil)]
      (is (string? (:connection/handle result)))
      (is (= :connected (:connector/status result)))
      (impl/do-close (:connection/handle result)))))

(deftest discover-test
  (testing "discover returns all resource schemas"
    (let [config {:github/org "test-org"}
          handle (:connection/handle (impl/do-connect config nil))
          result (impl/do-discover handle)]
      (is (= 6 (:discover/total-count result)))
      (is (= 6 (count (:schemas result))))
      (impl/do-close handle))))

(deftest extract-follows-link-pagination-test
  (testing "do-extract drains paginated GitHub responses before returning"
    (let [config {:github/owner "test-user" :github/repo "test-repo"}
          handle (:connection/handle (impl/do-connect config nil))
          calls  (atom [])]
      (try
        (with-redefs-fn
          {#'impl/do-request
           (fn [url _headers _params]
             (swap! calls conj url)
             (if (= "https://api.github.com/repos/test-user/test-repo/issues?page=2" url)
               (schema/success :body [{:number 2 :title "Second issue"}]
                               {:headers {}})
               (schema/success :body [{:number 1 :title "First issue"}]
                               {:headers {"link" "<https://api.github.com/repos/test-user/test-repo/issues?page=2>; rel=\"next\""}})))}
          (fn []
            (let [result (impl/do-extract handle "issues" {})]
              (is (= 2 (:extract/row-count result)))
              (is (false? (:extract/has-more result)))
              (is (= [1 2] (mapv :number (:records result))))
              (is (= 2 (count @calls))))))
        (finally
          (impl/do-close handle))))))

(deftest extract-reviews-fans-out-across-pulls-test
  (testing "review extraction enumerates pull reviews and preserves parent linkage"
    (let [handle (:connection/handle (impl/do-connect {:github/owner "test-user"
                                                       :github/repo "test-repo"} nil))
          pulls-url "https://api.github.com/repos/test-user/test-repo/pulls"
          review-url-1 "https://api.github.com/repos/test-user/test-repo/pulls/11/reviews"
          review-url-2 "https://api.github.com/repos/test-user/test-repo/pulls/22/reviews"]
      (try
        (with-redefs-fn
          {#'impl/do-request
           (fn [url _headers _params]
             (cond
               (= pulls-url url)
               (schema/success :body [{:id 101 :number 11
                                       :title "First pull"
                                       :state "open"
                                       :updated_at "2026-03-20T10:00:00Z"
                                       :html_url "https://github.com/test-user/test-repo/pull/11"}
                                      {:id 202 :number 22
                                       :title "Second pull"
                                       :state "closed"
                                       :updated_at "2026-03-20T11:00:00Z"
                                       :html_url "https://github.com/test-user/test-repo/pull/22"}]
                               {:headers {}})

               (= review-url-1 url)
               (schema/success :body [{:id 1001
                                       :body "Looks good"
                                       :submitted_at "2026-03-20T10:30:00Z"
                                       :state "APPROVED"}]
                               {:headers {}})

               (= review-url-2 url)
               (schema/success :body [{:id 1002
                                       :body "Needs one more test"
                                       :submitted_at "2026-03-20T11:30:00Z"
                                       :state "CHANGES_REQUESTED"}]
                               {:headers {}})

               :else
               (throw (ex-info "Unexpected URL" {:url url}))))}
          (fn []
            (let [result (impl/do-extract handle "reviews" {})
                  records (:records result)]
              (is (= 2 (:extract/row-count result)))
              (is (= [1001 1002] (mapv :id records)))
              (is (= [11 22] (mapv :pull_number records)))
              (is (= ["First pull" "Second pull"] (mapv :pull_title records)))
              (is (= {:cursor/type :timestamp-watermark
                      :cursor/value "2026-03-20T11:30:00Z"}
                     (:extract/cursor result))))))
        (finally
          (impl/do-close handle))))))

(deftest extract-reviews-filters-by-cursor-test
  (testing "review extraction filters out reviews at or before the prior cursor"
    (let [handle (:connection/handle (impl/do-connect {:github/owner "test-user"
                                                       :github/repo "test-repo"} nil))
          pulls-url "https://api.github.com/repos/test-user/test-repo/pulls"
          review-url "https://api.github.com/repos/test-user/test-repo/pulls/11/reviews"]
      (try
        (with-redefs-fn
          {#'impl/do-request
           (fn [url _headers _params]
             (cond
               (= pulls-url url)
               (schema/success :body [{:id 101 :number 11
                                       :title "First pull"
                                       :updated_at "2026-03-21T10:00:00Z"
                                       :html_url "https://github.com/test-user/test-repo/pull/11"}]
                               {:headers {}})

               (= review-url url)
               (schema/success :body [{:id 1001
                                       :body "Older review"
                                       :submitted_at "2026-03-21T09:59:00Z"
                                       :state "COMMENTED"}
                                      {:id 1002
                                       :body "Fresh review"
                                       :submitted_at "2026-03-21T10:30:00Z"
                                       :state "APPROVED"}]
                               {:headers {}})

               :else
               (throw (ex-info "Unexpected URL" {:url url}))))}
          (fn []
            (let [result (impl/do-extract handle "reviews"
                                          {:extract/cursor {:cursor/type :timestamp-watermark
                                                            :cursor/value "2026-03-21T10:00:00Z"}})]
              (is (= 1 (:extract/row-count result)))
              (is (= [1002] (mapv :id (:records result))))
              (is (= {:cursor/type :timestamp-watermark
                      :cursor/value "2026-03-21T10:30:00Z"}
                     (:extract/cursor result))))))
        (finally
          (impl/do-close handle))))))

(deftest checkpoint-test
  (testing "checkpoint returns committed result"
    (let [cursor {:cursor/type :timestamp-watermark :cursor/value "2026-01-01T00:00:00Z"}
          result (impl/do-checkpoint cursor)]
      (is (= :committed (:checkpoint/status result)))
      (is (= cursor (:checkpoint/cursor result))))))

;;------------------------------------------------------------------------------ Layer 1
;; Issue filtering tests

(deftest issue-filtering-test
  (testing "issue-not-pr? returns true for plain issues"
    (is (true? (#'impl/issue-not-pr? {:number 1 :title "Bug"}))))

  (testing "issue-not-pr? returns false for PRs in issues endpoint"
    (is (false? (#'impl/issue-not-pr? {:number 2 :title "PR" :pull_request {:url "..."}}))))

  (testing "filter-issues removes PRs from :issues resource"
    (let [records [{:number 1 :title "Issue"}
                   {:number 2 :title "PR" :pull_request {:url "..."}}
                   {:number 3 :title "Another issue"}]
          filtered (#'impl/filter-issues :issues records)]
      (is (= 2 (count filtered)))
      (is (every? #(nil? (:pull_request %)) filtered))))

  (testing "filter-issues passes through non-issues resources unchanged"
    (let [records [{:number 1 :pull_request {:url "..."}}
                   {:number 2 :pull_request {:url "..."}}]
          result (#'impl/filter-issues :pulls records)]
      (is (= 2 (count result))))))

;;------------------------------------------------------------------------------ Layer 1
;; Rate limit header integration tests

(deftest rate-limit-header-capture-test
  (testing "update-rate-limits! stores parsed rate info in handle"
    (let [handle "test-handle"
          ;; Manually store a handle
          _ (impl/store-handle! handle {:config {} :auth-headers {} :last-request-at nil})
          headers {"x-ratelimit-remaining" "4995"
                   "x-ratelimit-reset" "1711468800"
                   "x-ratelimit-limit" "5000"}]
      (#'impl/update-rate-limits! handle headers)
      (let [state (impl/get-handle handle)]
        (is (= 4995 (get-in state [:rate-limit :remaining])))
        (is (= 1711468800 (get-in state [:rate-limit :reset-epoch]))))
      (impl/remove-handle! handle))))

;;------------------------------------------------------------------------------ Layer 1
;; ETag integration tests

(deftest etag-integration-test
  (testing "ETag cache starts empty for unknown URLs"
    (etag/clear-cache!)
    (is (nil? (etag/get-etag "https://api.github.com/repos/test/test/issues"))))

  (testing "add-etag-header is no-op without cached etag"
    (etag/clear-cache!)
    (let [headers (#'impl/github-headers {})]
      (is (nil? (get (etag/add-etag-header headers "https://example.com") "If-None-Match")))))

  (testing "add-etag-header includes cached etag"
    (etag/clear-cache!)
    (etag/store-etag! "https://example.com" "W/\"test-etag\"")
    (let [headers (etag/add-etag-header {} "https://example.com")]
      (is (= "W/\"test-etag\"" (get headers "If-None-Match"))))))
