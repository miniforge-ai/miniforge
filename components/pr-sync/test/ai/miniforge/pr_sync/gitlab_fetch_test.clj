(ns ai.miniforge.pr-sync.gitlab-fetch-test
  "Unit tests for GitLab MR fetching and enrichment via mocked glab CLI."
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.java.shell]
   [cheshire.core :as json]
   [ai.miniforge.pr-sync.core :as core]))

;; ─────────────────────────────────────────────────────────────────────────────
;; Sample GitLab API responses
;; ─────────────────────────────────────────────────────────────────────────────

(def sample-open-mr
  {:iid 42
   :title "Add auth"
   :web_url "https://gitlab.com/grp/proj/-/merge_requests/42"
   :state "opened"
   :source_branch "feat/auth"
   :draft false
   :merge_status "can_be_merged"
   :head_pipeline {:status "success"}
   :author {:username "dev"}
   :additions 0 :deletions 0 :changes_count 1})

(def sample-closed-mr
  {:iid 10
   :title "Old fix"
   :web_url "https://gitlab.com/grp/proj/-/merge_requests/10"
   :state "closed"
   :source_branch "fix/old"
   :draft false
   :merge_status "cannot_be_merged"
   :head_pipeline nil
   :author {:username "dev"}})

(def sample-merged-mr
  {:iid 5
   :title "Initial"
   :web_url "https://gitlab.com/grp/proj/-/merge_requests/5"
   :state "merged"
   :source_branch "feat/init"
   :draft false
   :merge_status "can_be_merged"
   :head_pipeline {:status "success"}
   :author {:username "dev"}})

(defn glab-sh-mock
  "Build a mock sh that responds to glab api calls with the given JSON body."
  [json-body]
  (fn [& args]
    (if (= "glab" (first args))
      {:exit 0 :out (json/generate-string json-body) :err ""}
      {:exit 1 :out "" :err "wrong binary"})))

(defn glab-sh-failure
  [err-msg]
  (fn [& args]
    (if (= "glab" (first args))
      {:exit 1 :out "" :err err-msg}
      {:exit 1 :out "" :err "wrong binary"})))

;; ─────────────────────────────────────────────────────────────────────────────
;; fetch-gitlab-mrs-by-state — success
;; ─────────────────────────────────────────────────────────────────────────────

(deftest fetch-gitlab-mrs-by-state-open-test
  (testing "Fetches open MRs and returns normalized TrainPR maps"
    (with-redefs [clojure.java.shell/sh
                  (fn [& args]
                    (cond
                      ;; REST list endpoint
                      (some #(and (string? %) (.contains ^String % "merge_requests")) args)
                      {:exit 0 :out (json/generate-string [sample-open-mr]) :err ""}
                      ;; GraphQL diff stats endpoint
                      (some #(and (string? %) (.contains ^String % "graphql")) args)
                      {:exit 0 :out (json/generate-string
                                     {:data {:project {:mr42 {:diffStatsSummary
                                                              {:additions 15 :deletions 3 :fileCount 2}}}}})
                       :err ""}
                      :else {:exit 1 :out "" :err "unmatched"}))]
      (let [result (core/fetch-gitlab-mrs-by-state "gitlab:grp/proj" :open)]
        (is (true? (:success? result)))
        (is (= :gitlab (:provider result)))
        (is (= "gitlab:grp/proj" (:repo result)))
        (is (= 1 (count (:prs result))))
        (let [pr (first (:prs result))]
          (is (= 42 (:pr/number pr)))
          (is (= "Add auth" (:pr/title pr)))
          ;; Enriched diff stats
          (is (= 15 (:pr/additions pr)))
          (is (= 3 (:pr/deletions pr))))))))

(deftest fetch-gitlab-mrs-by-state-closed-test
  (testing "Closed state does not enrich with diff stats"
    (with-redefs [clojure.java.shell/sh
                  (glab-sh-mock [sample-closed-mr])]
      (let [result (core/fetch-gitlab-mrs-by-state "gitlab:grp/proj" :closed)]
        (is (true? (:success? result)))
        (is (= 1 (count (:prs result))))
        (is (= :closed (:pr/status (first (:prs result)))))))))

(deftest fetch-gitlab-mrs-by-state-merged-test
  (testing "Merged MRs are returned for :merged state"
    (with-redefs [clojure.java.shell/sh
                  (glab-sh-mock [sample-merged-mr])]
      (let [result (core/fetch-gitlab-mrs-by-state "gitlab:grp/proj" :merged)]
        (is (true? (:success? result)))
        (is (= 1 (count (:prs result))))
        (is (= :merged (:pr/status (first (:prs result)))))))))

(deftest fetch-gitlab-mrs-by-state-all-test
  (testing ":all state returns open, closed, and merged MRs"
    (with-redefs [clojure.java.shell/sh
                  (fn [& args]
                    (cond
                      (some #(and (string? %) (.contains ^String % "merge_requests")) args)
                      {:exit 0 :out (json/generate-string [sample-open-mr sample-closed-mr sample-merged-mr]) :err ""}
                      ;; GraphQL diff stats for open MR
                      (some #(and (string? %) (.contains ^String % "graphql")) args)
                      {:exit 0 :out (json/generate-string {:data {:project {}}}) :err ""}
                      :else {:exit 1 :out "" :err ""}))] 
      (let [result (core/fetch-gitlab-mrs-by-state "gitlab:grp/proj" :all)]
        (is (true? (:success? result)))
        ;; :all returns everything, no post-filtering
        (is (= 3 (count (:prs result))))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; fetch-gitlab-mrs-by-state — failure
;; ─────────────────────────────────────────────────────────────────────────────

(deftest fetch-gitlab-mrs-by-state-cli-failure-test
  (testing "CLI failure returns failure result with provider"
    (with-redefs [clojure.java.shell/sh
                  (glab-sh-failure "HTTP 401 Unauthorized")]
      (let [result (core/fetch-gitlab-mrs-by-state "gitlab:grp/proj" :open)]
        (is (false? (:success? result)))
        (is (= :gitlab (:provider result)))
        (is (string? (:error result)))))))

(deftest fetch-gitlab-mrs-by-state-malformed-json-test
  (testing "Malformed JSON returns parse failure"
    (with-redefs [clojure.java.shell/sh
                  (fn [& args]
                    (if (= "glab" (first args))
                      {:exit 0 :out "NOT JSON{" :err ""}
                      {:exit 1 :out "" :err ""}))]
      (let [result (core/fetch-gitlab-mrs-by-state "gitlab:grp/proj" :open)]
        (is (false? (:success? result)))
        (is (.contains ^String (:error result) "parse"))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; fetch-open-gitlab-mrs delegates to fetch-gitlab-mrs-by-state
;; ─────────────────────────────────────────────────────────────────────────────

(deftest fetch-open-gitlab-mrs-test
  (testing "fetch-open-gitlab-mrs returns only open/draft MRs"
    (with-redefs [clojure.java.shell/sh
                  (fn [& args]
                    (cond
                      (some #(and (string? %) (.contains ^String % "merge_requests")) args)
                      {:exit 0 :out (json/generate-string [sample-open-mr]) :err ""}
                      (some #(and (string? %) (.contains ^String % "graphql")) args)
                      {:exit 0 :out (json/generate-string {:data {:project {}}}) :err ""}
                      :else {:exit 1 :out "" :err ""}))]
      (let [result (core/fetch-open-gitlab-mrs "gitlab:grp/proj")]
        (is (true? (:success? result)))
        (is (= 1 (count (:prs result))))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; fetch-prs-by-state dispatches GitLab
;; ─────────────────────────────────────────────────────────────────────────────

(deftest fetch-prs-by-state-gitlab-dispatch-test
  (testing "fetch-prs-by-state dispatches to GitLab for gitlab: repos"
    (with-redefs [clojure.java.shell/sh
                  (glab-sh-mock [sample-merged-mr])]
      (let [result (core/fetch-prs-by-state "gitlab:grp/proj" :merged)]
        (is (true? (:success? result)))
        (is (= :gitlab (:provider result)))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; State filtering — open state excludes closed/merged
;; ─────────────────────────────────────────────────────────────────────────────

(deftest fetch-gitlab-open-filters-closed-mrs-test
  (testing "Open fetch filters out any closed/merged MRs leaked by API"
    (with-redefs [clojure.java.shell/sh
                  (fn [& args]
                    (cond
                      (some #(and (string? %) (.contains ^String % "merge_requests")) args)
                      ;; API leaks a closed MR alongside the open one
                      {:exit 0 :out (json/generate-string [sample-open-mr sample-closed-mr]) :err ""}
                      (some #(and (string? %) (.contains ^String % "graphql")) args)
                      {:exit 0 :out (json/generate-string {:data {:project {}}}) :err ""}
                      :else {:exit 1 :out "" :err ""}))]
      (let [result (core/fetch-gitlab-mrs-by-state "gitlab:grp/proj" :open)
            statuses (set (map :pr/status (:prs result)))]
        (is (true? (:success? result)))
        ;; Closed should be filtered out
        (is (not (contains? statuses :closed)))
        (is (not (contains? statuses :merged)))))))
