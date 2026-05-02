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

(ns ai.miniforge.connector-jira.impl-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.connector-jira.impl :as impl]
            [ai.miniforge.connector-jira.resources :as resources]
            [ai.miniforge.connector-jira.schema :as jira-schema]
            [ai.miniforge.schema.interface :as schema]))

;;------------------------------------------------------------------------------ Layer 0
;; Resource registry tests

(deftest resource-schemas-test
  (testing "resource-schemas returns all known resources"
    (let [schemas (resources/resource-schemas)]
      (is (= 5 (count schemas)))
      (is (every? :schema/name schemas))
      (is (some #(= "issues" (:schema/name %)) schemas))
      (is (some #(= "projects" (:schema/name %)) schemas))
      (is (some #(= "boards" (:schema/name %)) schemas))
      (is (some #(= "sprints" (:schema/name %)) schemas))
      (is (some #(= "comments" (:schema/name %)) schemas)))))

;;------------------------------------------------------------------------------ Layer 1
;; URL building tests

(deftest build-base-url-test
  (testing "builds classic site URL from :jira/site"
    (is (= "https://mycompany.atlassian.net"
           (resources/build-base-url {:jira/site "mycompany"}))))

  (testing "builds API gateway URL when :jira/cloud-id is present"
    (is (= "https://api.atlassian.com/ex/jira/abc-123"
           (resources/build-base-url {:jira/site "mycompany" :jira/cloud-id "abc-123"})))))

(deftest build-url-test
  (testing "builds issues search URL"
    (let [resource-def (resources/get-resource :issues)
          url (resources/build-url "https://myco.atlassian.net" resource-def {})]
      (is (= "https://myco.atlassian.net/rest/api/3/search/jql" url))))

  (testing "builds projects URL"
    (let [resource-def (resources/get-resource :projects)
          url (resources/build-url "https://myco.atlassian.net" resource-def {})]
      (is (= "https://myco.atlassian.net/rest/api/3/project/search" url))))

  (testing "builds boards URL"
    (let [resource-def (resources/get-resource :boards)
          url (resources/build-url "https://myco.atlassian.net" resource-def {})]
      (is (= "https://myco.atlassian.net/rest/agile/1.0/board" url))))

  (testing "builds sprints URL with board-id"
    (let [resource-def (resources/get-resource :sprints)
          config {:jira/board-id 42}
          url (resources/build-url "https://myco.atlassian.net" resource-def config)]
      (is (= "https://myco.atlassian.net/rest/agile/1.0/board/42/sprint" url))))

  (testing "builds comments URL with issue-key"
    (let [resource-def (resources/get-resource :comments)
          config {:jira/issue-key "PROJ-123"}
          url (resources/build-url "https://myco.atlassian.net" resource-def config)]
      (is (= "https://myco.atlassian.net/rest/api/3/issue/PROJ-123/comment" url)))))

;;------------------------------------------------------------------------------ Layer 2
;; Query param building

(deftest build-query-params-test
  (testing "issues: includes JQL with project key and default fields"
    (let [resource-def (resources/get-resource :issues)
          params (resources/build-query-params resource-def nil {} {:jira/project-key "PROJ"})]
      (is (= 100 (get params "maxResults")))
      (is (clojure.string/includes? (get params "jql") "project = PROJ"))
      (is (clojure.string/includes? (get params "jql") "ORDER BY updated DESC"))
      (is (string? (get params "fields")))
      (is (clojure.string/includes? (get params "fields") "key"))))

  (testing "issues: includes cursor value in JQL"
    (let [resource-def (resources/get-resource :issues)
          cursor {:cursor/type :timestamp-watermark :cursor/value "2026-01-15 10:00"}
          params (resources/build-query-params resource-def cursor {} {:jira/project-key "PROJ"})]
      (is (clojure.string/includes? (get params "jql") "updated >= \"2026-01-15 10:00\""))))

  (testing "issues: no project clause when project-key is absent; sentinel date used"
    (let [resource-def (resources/get-resource :issues)
          params (resources/build-query-params resource-def nil {} {})]
      (is (not (clojure.string/includes? (get params "jql") "project")))
      (is (clojure.string/includes? (get params "jql") "updated >= \"2000-01-01"))
      (is (clojure.string/includes? (get params "jql") "ORDER BY updated DESC"))))

  (testing "issues: no project clause when project-key is unresolved placeholder; sentinel date used"
    (let [resource-def (resources/get-resource :issues)
          params (resources/build-query-params resource-def nil {} {:jira/project-key "${JIRA_PROJECT_KEY}"})]
      (is (not (clojure.string/includes? (get params "jql") "project")))
      (is (clojure.string/includes? (get params "jql") "updated >= \"2000-01-01"))
      (is (clojure.string/includes? (get params "jql") "ORDER BY updated DESC"))))

  (testing "non-issues: no JQL"
    (let [resource-def (resources/get-resource :boards)
          params (resources/build-query-params resource-def nil {} {})]
      (is (= 50 (get params "maxResults")))
      (is (nil? (get params "jql"))))))

;;------------------------------------------------------------------------------ Layer 3
;; Connect / close lifecycle

(deftest connect-validates-config-test
  (testing "do-connect requires :jira/site"
    (is (thrown? Exception (impl/do-connect {} nil)))))

(deftest connect-close-lifecycle-test
  (testing "connect and close with valid config"
    (let [result (impl/do-connect {:jira/site "mycompany"} nil)]
      (is (= :connected (:connector/status result)))
      (is (string? (:connection/handle result)))
      (let [close-result (impl/do-close (:connection/handle result))]
        (is (= :closed (:connector/status close-result)))))))

(deftest discover-test
  (testing "discover returns all resources"
    (let [handle (:connection/handle (impl/do-connect {:jira/site "mycompany"} nil))
          result (impl/do-discover handle)]
      (is (= 5 (:discover/total-count result)))
      (impl/do-close handle))))

;;------------------------------------------------------------------------------ Layer 4
;; Extract with mocked HTTP

(deftest extract-issues-offset-pagination-test
  (testing "do-extract drains offset-paginated Jira responses"
    (let [handle (:connection/handle (impl/do-connect {:jira/site "test"
                                                       :jira/project-key "PROJ"} nil))
          calls  (atom [])]
      (try
        (with-redefs-fn
          {#'impl/do-request
           (fn [url _headers params]
             (swap! calls conj params)
             (let [start-at (get params "startAt" 0)]
               (if (zero? start-at)
                 (schema/success :body {:issues [{:id "1" :key "PROJ-1"
                                                  :fields {:updated "2026-03-20T10:00:00.000+0000"}}
                                                 {:id "2" :key "PROJ-2"
                                                  :fields {:updated "2026-03-20T11:00:00.000+0000"}}]
                                        :startAt 0 :maxResults 2 :total 3}
                                 {:headers {}})
                 (schema/success :body {:issues [{:id "3" :key "PROJ-3"
                                                  :fields {:updated "2026-03-20T12:00:00.000+0000"}}]
                                        :startAt 2 :maxResults 2 :total 3}
                                 {:headers {}}))))}
          (fn []
            (let [result (impl/do-extract handle "issues" {})]
              (is (= 3 (:extract/row-count result)))
              (is (false? (:extract/has-more result)))
              (is (= ["PROJ-1" "PROJ-2" "PROJ-3"] (mapv :key (:records result))))
              ;; Two pages fetched
              (is (= 2 (count @calls))))))
        (finally
          (impl/do-close handle))))))

(deftest extract-projects-test
  (testing "do-extract for projects uses :values response key"
    (let [handle (:connection/handle (impl/do-connect {:jira/site "test"} nil))]
      (try
        (with-redefs-fn
          {#'impl/do-request
           (fn [_url _headers _params]
             (schema/success :body {:values [{:id "10001" :key "PROJ" :name "My Project"}]
                                    :startAt 0 :maxResults 50 :total 1}
                             {:headers {}}))}
          (fn []
            (let [result (impl/do-extract handle "projects" {})]
              (is (= 1 (:extract/row-count result)))
              (is (= "PROJ" (:key (first (:records result))))))))
        (finally
          (impl/do-close handle))))))

(deftest extract-unknown-resource-test
  (testing "do-extract throws for unknown resource"
    (let [handle (:connection/handle (impl/do-connect {:jira/site "test"} nil))]
      (try
        (is (thrown? Exception (impl/do-extract handle "nonexistent" {})))
        (finally
          (impl/do-close handle))))))

(deftest checkpoint-test
  (testing "checkpoint returns committed"
    (let [cursor {:cursor/type :timestamp-watermark :cursor/value "2026-01-01 00:00"}
          result (impl/do-checkpoint cursor)]
      (is (= :committed (:checkpoint/status result))))))

;;------------------------------------------------------------------------------ Layer 5
;; Boundary schema validation

(deftest config-schema-validation-test
  (testing "valid config passes"
    (is (:valid? (jira-schema/validate jira-schema/JiraConfig
                                       {:jira/site "mycompany"
                                        :jira/project-key "PROJ"}))))

  (testing "missing :jira/site fails"
    (is (not (:valid? (jira-schema/validate jira-schema/JiraConfig
                                            {:jira/project-key "PROJ"})))))

  (testing "extra keys are allowed (open map)"
    (is (:valid? (jira-schema/validate jira-schema/JiraConfig
                                       {:jira/site "mycompany"
                                        :auth/method :basic})))))

(deftest response-schema-validation-test
  (testing "valid paginated response passes"
    (is (:valid? (jira-schema/validate-response
                  {:startAt 0 :maxResults 50 :total 100}))))

  (testing "missing :total fails"
    (is (not (:valid? (jira-schema/validate-response
                       {:startAt 0 :maxResults 50}))))))

(deftest issue-schema-validation-test
  (testing "valid issue passes"
    (is (:valid? (jira-schema/validate jira-schema/JiraIssue
                                       {:id "10001" :key "PROJ-1"
                                        :fields {:summary "A bug"
                                                 :updated "2026-03-20T10:00:00.000+0000"}}))))

  (testing "issue missing :key fails"
    (is (not (:valid? (jira-schema/validate jira-schema/JiraIssue
                                            {:id "10001"
                                             :fields {:summary "A bug"}})))))

  (testing "issue missing :id fails"
    (is (not (:valid? (jira-schema/validate jira-schema/JiraIssue
                                            {:key "PROJ-1"
                                             :fields {:summary "A bug"}}))))))

(deftest validate-records-filters-invalid-test
  (testing "valid records pass through"
    (let [records [{:id "1" :key "PROJ-1" :fields {:summary "ok"}}
                   {:id "2" :key "PROJ-2" :fields {:summary "also ok"}}]]
      (is (= 2 (count (jira-schema/validate-records :issues records))))))

  (testing "invalid records are filtered out"
    (let [records [{:id "1" :key "PROJ-1" :fields {:summary "ok"}}
                   {:garbage true}
                   {:id "3" :key "PROJ-3" :fields {:summary "fine"}}]]
      (is (= 2 (count (jira-schema/validate-records :issues records))))))

  (testing "unknown resource key returns records unchanged"
    (let [records [{:anything "goes"}]]
      (is (= 1 (count (jira-schema/validate-records :unknown records)))))))

(deftest extract-filters-malformed-api-records-test
  (testing "extract drops records that fail schema validation"
    (let [handle (:connection/handle (impl/do-connect {:jira/site "test"
                                                       :jira/project-key "PROJ"} nil))]
      (try
        (with-redefs-fn
          {#'impl/do-request
           (fn [_url _headers _params]
             (schema/success :body {:issues [;; Valid
                                             {:id "1" :key "PROJ-1"
                                              :fields {:updated "2026-03-20T10:00:00.000+0000"}}
                                             ;; Malformed — missing :id and :key
                                             {:fields {:summary "broken"}}
                                             ;; Valid
                                             {:id "3" :key "PROJ-3"
                                              :fields {:updated "2026-03-20T12:00:00.000+0000"}}]
                                    :startAt 0 :maxResults 100 :total 3}
                             {:headers {}}))}
          (fn []
            (let [result (impl/do-extract handle "issues" {})]
              ;; Only 2 valid records survive
              (is (= 2 (:extract/row-count result)))
              (is (= ["PROJ-1" "PROJ-3"] (mapv :key (:records result)))))))
        (finally
          (impl/do-close handle))))))

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
      (impl/do-connect {:jira/site "mycompany"}
                       {:auth/method :api-key
                        ;; Missing :auth/credential-id — connector-auth flags this
                        :auth/scheme :basic})
      (is false "expected ex-info")
      (catch clojure.lang.ExceptionInfo e
        (is (some? (:errors (ex-data e))))))))

(deftest connect-accepts-valid-auth-test
  (testing "do-connect succeeds when auth credential-ref validates"
    (let [result (impl/do-connect {:jira/site "mycompany"}
                                  {:auth/method        :api-key
                                   :auth/credential-id "test-token"})]
      (is (= :connected (:connector/status result)))
      (impl/do-close (:connection/handle result)))))
