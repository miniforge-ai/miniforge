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

(ns ai.miniforge.connector-gitlab.impl-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.connector-gitlab.impl :as impl]
            [ai.miniforge.connector-gitlab.resources :as resources]
            [ai.miniforge.schema.interface :as schema]))

;;------------------------------------------------------------------------------ Layer 0
;; Fixtures

;;------------------------------------------------------------------------------ Layer 1
;; Tests

(deftest resource-schemas-test
  (testing "resource-schemas returns all known resources"
    (let [schemas (resources/resource-schemas)]
      (is (= 8 (count schemas)))
      (is (every? :schema/name schemas))
      (is (some #(= "merge-requests" (:schema/name %)) schemas))
      (is (some #(= "issues" (:schema/name %)) schemas))
      (is (some #(= "milestones" (:schema/name %)) schemas))
      (is (some #(= "iterations" (:schema/name %)) schemas))
      (is (some #(= "boards" (:schema/name %)) schemas))
      (is (some #(= "pipelines" (:schema/name %)) schemas))
      (is (some #(= "commits" (:schema/name %)) schemas))
      (is (some #(= "notes" (:schema/name %)) schemas)))))

(deftest build-url-test
  (testing "builds project MR URL with project-path"
    (let [resource-def (resources/get-resource :merge-requests)
          config {:gitlab/project-path "engrammicai/ixi-services/services/ixi"}
          url (resources/build-url "https://gitlab.com" resource-def config)]
      (is (clojure.string/includes? url "/api/v4/projects/"))
      (is (clojure.string/includes? url "/merge_requests"))))

  (testing "builds project URL with project-id"
    (let [resource-def (resources/get-resource :issues)
          config {:gitlab/project-id 42376374}
          url (resources/build-url "https://gitlab.com" resource-def config)]
      (is (= "https://gitlab.com/api/v4/projects/42376374/issues" url))))

  (testing "builds commits URL"
    (let [resource-def (resources/get-resource :commits)
          config {:gitlab/project-id 123}
          url (resources/build-url "https://gitlab.com" resource-def config)]
      (is (= "https://gitlab.com/api/v4/projects/123/repository/commits" url))))

  (testing "builds milestones URL"
    (let [resource-def (resources/get-resource :milestones)
          config {:gitlab/project-id 123}
          url (resources/build-url "https://gitlab.com" resource-def config)]
      (is (= "https://gitlab.com/api/v4/projects/123/milestones" url))))

  (testing "builds iterations URL"
    (let [resource-def (resources/get-resource :iterations)
          config {:gitlab/project-id 123}
          url (resources/build-url "https://gitlab.com" resource-def config)]
      (is (= "https://gitlab.com/api/v4/projects/123/iterations" url))))

  (testing "builds boards URL"
    (let [resource-def (resources/get-resource :boards)
          config {:gitlab/project-id 123}
          url (resources/build-url "https://gitlab.com" resource-def config)]
      (is (= "https://gitlab.com/api/v4/projects/123/boards" url))))

  (testing "builds note URL"
    (let [resource-def (resources/get-resource :notes)
          config {:gitlab/project-id 123
                  :gitlab/noteable-kind "merge_requests"
                  :gitlab/noteable-iid 42}
          url (resources/build-url "https://gitlab.com" resource-def config)]
      (is (= "https://gitlab.com/api/v4/projects/123/merge_requests/42/notes" url)))))

(deftest build-query-params-test
  (testing "includes defaults and per_page"
    (let [resource-def (resources/get-resource :issues)
          params (resources/build-query-params resource-def nil {})]
      (is (= 100 (get params "per_page")))
      (is (= "all" (get params :state)))
      (is (= "updated_at" (get params "order_by")))
      (is (= "desc" (get params "sort"))))))

  (testing "includes cursor for incremental"
    (let [resource-def (resources/get-resource :merge-requests)
          cursor {:cursor/type :timestamp-watermark :cursor/value "2026-01-01T00:00:00Z"}
          params (resources/build-query-params resource-def cursor {})]
      (is (= "2026-01-01T00:00:00Z" (get params "updated_after")))))

  (testing "boards omit incremental params"
    (let [resource-def (resources/get-resource :boards)
          cursor {:cursor/type :offset :cursor/value 100}
          params (resources/build-query-params resource-def cursor {})]
      (is (nil? (get params "updated_after")))
      (is (= 100 (get params "per_page")))))

(deftest connect-validates-config-test
  (testing "do-connect requires project-id or project-path"
    (is (thrown? Exception (impl/do-connect {} nil)))))

(deftest connect-close-lifecycle-test
  (testing "connect and close with project-path"
    (let [result (impl/do-connect {:gitlab/project-path "mygroup/myrepo"} nil)]
      (is (= :connected (:connector/status result)))
      (is (string? (:connection/handle result)))
      (let [close-result (impl/do-close (:connection/handle result))]
        (is (= :closed (:connector/status close-result)))))))

(deftest discover-test
  (testing "discover returns all resources"
    (let [handle (:connection/handle (impl/do-connect {:gitlab/project-id 1} nil))
          result (impl/do-discover handle)]
      (is (= 8 (:discover/total-count result)))
      (impl/do-close handle))))

(deftest extract-follows-link-pagination-test
  (testing "do-extract drains paginated GitLab responses before returning"
    (let [handle (:connection/handle (impl/do-connect {:gitlab/project-id 1} nil))
          calls  (atom [])]
      (try
        (with-redefs-fn
          {#'impl/do-request
           (fn [url _headers _params]
             (swap! calls conj url)
             (if (= "https://gitlab.com/api/v4/projects/1/issues?page=2" url)
               (schema/success :body [{:iid 2 :project_id 1 :title "Second"}]
                               {:headers {}})
               (schema/success :body [{:iid 1 :project_id 1 :title "First"}]
                               {:headers {"link" "<https://gitlab.com/api/v4/projects/1/issues?page=2>; rel=\"next\""}})))}
          (fn []
            (let [result (impl/do-extract handle "issues" {})]
              (is (= 2 (:extract/row-count result)))
              (is (false? (:extract/has-more result)))
              (is (= [1 2] (mapv :iid (:records result))))
              (is (= 2 (count @calls))))))
        (finally
          (impl/do-close handle))))))

(deftest extract-notes-fans-out-across-parent-records-test
  (testing "notes extraction enumerates issue and merge request notes with parent linkage"
    (let [handle (:connection/handle (impl/do-connect {:gitlab/project-id 1} nil))
          issue-url "https://gitlab.com/api/v4/projects/1/issues"
          mr-url "https://gitlab.com/api/v4/projects/1/merge_requests"
          issue-note-url "https://gitlab.com/api/v4/projects/1/issues/11/notes"
          mr-note-url "https://gitlab.com/api/v4/projects/1/merge_requests/22/notes"]
      (try
        (with-redefs-fn
          {#'impl/do-request
           (fn [url _headers _params]
             (cond
               (= issue-url url)
               (schema/success :body [{:id 101 :iid 11 :project_id 1
                                       :title "Issue parent"
                                       :state "opened"
                                       :updated_at "2026-03-20T10:00:00Z"
                                       :web_url "https://gitlab.com/group/repo/-/issues/11"}]
                               {:headers {}})

               (= mr-url url)
               (schema/success :body [{:id 202 :iid 22 :project_id 1
                                       :title "MR parent"
                                       :state "merged"
                                       :updated_at "2026-03-20T11:00:00Z"
                                       :web_url "https://gitlab.com/group/repo/-/merge_requests/22"}]
                               {:headers {}})

               (= issue-note-url url)
               (schema/success :body [{:id 1001
                                       :body "Issue note"
                                       :updated_at "2026-03-20T10:30:00Z"
                                       :created_at "2026-03-20T10:30:00Z"}]
                               {:headers {}})

               (= mr-note-url url)
               (schema/success :body [{:id 1002
                                       :body "MR note"
                                       :updated_at "2026-03-20T11:30:00Z"
                                       :created_at "2026-03-20T11:30:00Z"}]
                               {:headers {}})

               :else
               (throw (ex-info "Unexpected URL" {:url url}))))}
          (fn []
            (let [result (impl/do-extract handle "notes" {})
                  records (:records result)]
              (is (= 2 (:extract/row-count result)))
              (is (= [1001 1002] (mapv :id records)))
              (is (= [11 22] (mapv :noteable_iid records)))
              (is (= ["Issue" "MergeRequest"] (mapv :noteable_type records)))
              (is (= ["Issue parent" "MR parent"] (mapv :noteable_title records)))
              (is (= {:cursor/type :timestamp-watermark
                      :cursor/value "2026-03-20T11:30:00Z"}
                     (:extract/cursor result))))))
        (finally
          (impl/do-close handle))))))

(deftest extract-notes-filters-by-cursor-test
  (testing "notes extraction filters out notes at or before the prior cursor"
    (let [handle (:connection/handle (impl/do-connect {:gitlab/project-id 1} nil))
          issue-url "https://gitlab.com/api/v4/projects/1/issues"
          mr-url "https://gitlab.com/api/v4/projects/1/merge_requests"
          issue-note-url "https://gitlab.com/api/v4/projects/1/issues/11/notes"]
      (try
        (with-redefs-fn
          {#'impl/do-request
           (fn [url _headers _params]
             (cond
               (= issue-url url)
               (schema/success :body [{:id 101 :iid 11 :project_id 1
                                       :title "Issue parent"
                                       :updated_at "2026-03-21T10:00:00Z"
                                       :web_url "https://gitlab.com/group/repo/-/issues/11"}]
                               {:headers {}})

               (= mr-url url)
               (schema/success :body [] {:headers {}})

               (= issue-note-url url)
               (schema/success :body [{:id 1001
                                       :body "Old note"
                                       :updated_at "2026-03-21T09:59:00Z"
                                       :created_at "2026-03-21T09:59:00Z"}
                                      {:id 1002
                                       :body "Fresh note"
                                       :updated_at "2026-03-21T10:30:00Z"
                                       :created_at "2026-03-21T10:30:00Z"}]
                               {:headers {}})

               :else
               (throw (ex-info "Unexpected URL" {:url url}))))}
          (fn []
            (let [result (impl/do-extract handle "notes"
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
  (testing "checkpoint returns committed"
    (let [cursor {:cursor/type :timestamp-watermark :cursor/value "2026-01-01T00:00:00Z"}
          result (impl/do-checkpoint cursor)]
      (is (= :committed (:checkpoint/status result))))))

;;------------------------------------------------------------------------------ Layer 6
;; Migrated validation helpers — confirm the shared connector helpers
;; preserve the legacy ex-info shape at the protocol boundary.

(deftest discover-throws-on-unknown-handle-test
  (testing "do-discover throws ex-info with :handle key when handle missing"
    (try
      (impl/do-discover "no-such-handle")
      (is false "expected ex-info")
      (catch clojure.lang.ExceptionInfo e
        (is (= "no-such-handle" (:handle (ex-data e))))))))

(deftest extract-throws-on-unknown-handle-test
  (testing "do-extract throws ex-info with :handle key when handle missing"
    (try
      (impl/do-extract "no-such-handle" "issues" {})
      (is false "expected ex-info")
      (catch clojure.lang.ExceptionInfo e
        (is (= "no-such-handle" (:handle (ex-data e))))))))

(deftest connect-rejects-malformed-auth-test
  (testing "do-connect throws ex-info when auth credential-ref is malformed"
    (try
      (impl/do-connect {:gitlab/project-path "owner/repo"}
                       {:auth/method :api-key
                        ;; Missing :auth/credential-id
                        :auth/scheme :bearer})
      (is false "expected ex-info")
      (catch clojure.lang.ExceptionInfo e
        (is (some? (:errors (ex-data e))))))))

(deftest connect-accepts-valid-auth-test
  (testing "do-connect succeeds when auth credential-ref validates"
    (let [result (impl/do-connect {:gitlab/project-path "owner/repo"}
                                  {:auth/method        :api-key
                                   :auth/credential-id "test-token"})]
      (is (= :connected (:connector/status result)))
      (impl/do-close (:connection/handle result)))))

(comment
  ;; Run: clj -M:dev:test -n ai.miniforge.connector-gitlab.impl-test
  )
