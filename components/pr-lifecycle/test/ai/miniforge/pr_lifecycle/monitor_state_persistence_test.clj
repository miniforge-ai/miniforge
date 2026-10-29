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
(ns ai.miniforge.pr-lifecycle.monitor-state-persistence-test
  "PR monitor budgets and watermarks on disk: kept per repository, merged
   when two monitors write them, and failing safe when damaged. Drives the
   monitor through create-monitor / run-cycle against a temporary miniforge
   home; GitHub and the comment handlers are stubbed."
  (:require
   [ai.miniforge.config.interface :as mf-config]
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.pr-lifecycle.events :as events]
   [ai.miniforge.pr-lifecycle.fix-loop :as fix-loop]
   [ai.miniforge.pr-lifecycle.monitor-budget :as budget]
   [ai.miniforge.pr-lifecycle.monitor-handlers :as handlers]
   [ai.miniforge.pr-lifecycle.monitor-loop :as monitor-loop]
   [ai.miniforge.pr-lifecycle.monitor-state :as state]
   [ai.miniforge.pr-lifecycle.monitor-store :as store]
   [ai.miniforge.pr-lifecycle.pr-poller :as poller]
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing use-fixtures]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} test-home
  "One temporary miniforge home for this namespace, emptied before each test."
  (str (fs/create-temp-dir {:prefix "mf-pr-monitor-state-"})))

(defn- ^{:stratum 0} monitor-for
  "A monitor of `repo-slug`; giving the slug keeps gh out of the test."
  ([repo-slug] (monitor-for repo-slug {}))
  ([repo-slug extra-config]
   (state/create-monitor (merge {:repo-slug repo-slug
                                 :self-author "miniforge[bot]"}
                                extra-config))))

(defn- ^{:stratum 0} review-comment
  [id created-at]
  {:comment/id id
   :comment/body "Please rename this function"
   :comment/author "alice"
   :comment/author-association "MEMBER"
   :comment/created-at created-at
   :comment/type :issue-comment})

(defn- ^{:stratum 0} run-cycle-with
  "One monitor cycle for PR `pr-number` while GitHub returns `comments`.
   Returns [cycle-result ids-of-comments-routed-to-a-handler]."
  [monitor pr-number comments]
  (let [routed (atom [])]
    (with-redefs [poller/fetch-pr-comments (fn [_ _] (dag/ok {:comments comments}))
                  handlers/route-comment   (fn [_ _ classified]
                                             (swap! routed conj (get-in classified [:comment :comment/id]))
                                             {:success? true})]
      [(monitor-loop/run-cycle monitor {:pr/number pr-number :pr/url (str "https://github.com/acme/app/pull/" pr-number)
                                        :pr/title "Test PR" :pr/branch "feat/test" :pr/sha "abc123"
                                        :pr/updated-at "2026-10-01T00:00:00Z"})
       @routed])))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} isolated-home
  [run-test]
  (fs/delete-tree (fs/path test-home "state"))
  (with-redefs [mf-config/miniforge-home (constantly test-home)]
    (run-test)))

(defn- ^{:stratum 1} file-named
  "The file called `file-name` somewhere under the test home."
  [file-name]
  (str (first (fs/glob test-home (str "**/" file-name)))))

(deftest ^{:stratum 1} budgets-are-kept-per-repo-test
  (let [monitor (monitor-for "acme/app")]
    (state/update-budget! monitor 42 (reduce budget/record-fix-attempt
                                             (state/get-or-create-budget monitor 42)
                                             (range 1 11)))
    (testing "PR #42 of another repository starts with a full budget"
      (let [other (state/get-or-create-budget (monitor-for "other/lib") 42)]
        (is (nil? (budget/any-budget-exhausted? other nil)))
        (is (zero? (:total-attempts other)))))
    (testing "a restarted monitor of the same repository keeps the spent budget"
      (is (= :pr-limit (budget/any-budget-exhausted?
                        (state/get-or-create-budget (monitor-for "acme/app") 42) nil))))))

(deftest ^{:stratum 1} watermarks-are-kept-per-repo-test
  (let [comments [(review-comment 1 "2026-10-01T00:00:00Z")]]
    (run-cycle-with (monitor-for "acme/app") 7 comments)
    (testing "a comment seen on PR #7 of one repository is new on PR #7 of another"
      (is (= 1 (:new-comments (first (run-cycle-with (monitor-for "other/lib") 7 comments))))))
    (testing "and not new to a restarted monitor of the same repository"
      (is (= 0 (:new-comments (first (run-cycle-with (monitor-for "acme/app") 7 comments))))))))

(deftest ^{:stratum 1} two-monitors-of-one-repo-keep-each-others-watermarks-test
  (let [first-monitor  (monitor-for "acme/app")
        second-monitor (monitor-for "acme/app")
        on-pr-1        [(review-comment 1 "2026-10-01T00:00:00Z")]
        on-pr-2        [(review-comment 2 "2026-10-02T00:00:00Z")]]
    (run-cycle-with first-monitor 1 on-pr-1)
    (run-cycle-with second-monitor 2 on-pr-2)
    (testing "a restarted monitor has seen the comments both monitors handled"
      (let [restarted (monitor-for "acme/app")]
        (is (= 0 (:new-comments (first (run-cycle-with restarted 1 on-pr-1)))))
        (is (= 0 (:new-comments (first (run-cycle-with restarted 2 on-pr-2)))))))))

(deftest ^{:stratum 1} unkeyed-files-from-older-versions-are-ignored-test
  (let [legacy-dir (fs/path test-home "state" "pr-monitor")]
    (fs/create-dirs legacy-dir)
    (spit (str (fs/path legacy-dir "budget-42.edn"))
          (pr-str (assoc (budget/create-budget 42) :total-attempts 10)))
    (spit (str (fs/path legacy-dir "watermarks.edn"))
          (pr-str {7 {:last-seen-at "2026-10-09T00:00:00Z" :advanced-at "2026-10-09"}}))
    (let [monitor (monitor-for "acme/app")]
      (testing "an unkeyed budget file is not applied to this repository's PR"
        (is (nil? (budget/any-budget-exhausted? (state/get-or-create-budget monitor 42) nil))))
      (testing "an unkeyed watermark is not applied to this repository's PR"
        (is (= 1 (:new-comments (first (run-cycle-with monitor 7 [(review-comment 1 "2026-10-01T00:00:00Z")])))))))))

(deftest ^{:stratum 1} monitor-without-a-repo-takes-no-action-test
  (let [[result routed] (run-cycle-with (state/create-monitor {:self-author "miniforge[bot]"}) 7
                                        [(review-comment 1 "2026-10-01T00:00:00Z")])]
    (is (true? (:stopped? result)))
    (is (empty? routed))))

(deftest ^{:stratum 1} unsaved-fix-attempt-is-not-started-test
  (let [bus     (events/create-event-bus)
        monitor (monitor-for "acme/app" {:event-bus bus :generate-fn (constantly "patch")})
        fixes   (atom 0)
        file    (store/repo-file "acme/app" "budget-42.edn")]
    (state/get-or-create-budget monitor 42)
    (fs/create-dirs (fs/parent file))
    (spit (str file) "[]")
    (with-redefs [fix-loop/run-fix-loop (fn [& _] (swap! fixes inc) {:success? true :commit-sha "abc123"})
                  poller/post-comment   (fn [& _] (dag/ok {:comment-posted true}))]
      (let [result (handlers/handle-change-request monitor {:pr/number 42}
                                                   {:category :change-request
                                                    :comment (review-comment 9 "2026-10-01T00:00:00Z")})]
        (testing "an attempt whose budget cannot be saved does not run"
          (is (zero? @fixes))
          (is (= :budget-unsaved (:reason result))))
        (testing "and is escalated"
          (is (some #(= :pr-monitor/escalated (:event/type %)) (:events @bus))))))))

(deftest ^{:stratum 1} two-monitors-share-one-budget-test
  (let [fixes     (atom 0)
        change    {:category :change-request :comment (review-comment 9 "2026-10-01T00:00:00Z")}
        monitor-a (monitor-for "acme/app" {:generate-fn (constantly "patch")})
        monitor-b (monitor-for "acme/app")
        saved-42  #(budget/load-budget "acme/app" 42)]
    (state/get-or-create-budget monitor-a 42)
    (state/get-or-create-budget monitor-a 43)
    (state/update-budget! monitor-b 42 (reduce budget/record-fix-attempt
                                               (state/get-or-create-budget monitor-b 42)
                                               (range 1 11)))
    (state/update-budget! monitor-b 43 (nth (iterate budget/record-fix-pushed
                                                     (state/get-or-create-budget monitor-b 43))
                                            4))
    (with-redefs [fix-loop/run-fix-loop (fn [& _] (swap! fixes inc) {:success? true :commit-sha "abc123"})
                  poller/post-comment   (fn [& _] (dag/ok {:comment-posted true}))]
      (testing "a monitor whose cached budget is stale does not start a fix once another spent it"
        (is (= :budget-exhausted (:reason (handlers/handle-change-request monitor-a {:pr/number 42} change))))
        (is (zero? @fixes))
        (is (= 10 (:total-attempts (saved-42)))))
      (testing "a pushed fix is counted on top of what the other monitor saved"
        (handlers/handle-change-request monitor-a {:pr/number 43} change)
        (is (= 1 @fixes))
        (is (= 5 (:fixes-pushed (budget/load-budget "acme/app" 43))))))))

(deftest ^{:stratum 1} lower-limit-saved-by-another-monitor-holds-test
  (let [fixes     (atom 0)
        monitor-a (monitor-for "acme/app" {:generate-fn (constantly "patch")})
        monitor-b (monitor-for "acme/app" {:max-total-fix-attempts-per-pr 2})]
    (state/get-or-create-budget monitor-a 42)
    (state/update-budget! monitor-b 42 (reduce budget/record-fix-attempt
                                               (state/get-or-create-budget monitor-b 42) [1 2]))
    (with-redefs [fix-loop/run-fix-loop (fn [& _] (swap! fixes inc) {:success? true :commit-sha "abc123"})
                  poller/post-comment   (fn [& _] (dag/ok {:comment-posted true}))]
      (testing "a budget exhausted under another monitor's lower limit stays exhausted"
        (is (= :budget-exhausted
               (:reason (handlers/handle-change-request
                         monitor-a {:pr/number 42}
                         {:category :change-request :comment (review-comment 9 "2026-10-01T00:00:00Z")}))))
        (is (zero? @fixes))))))

(deftest ^{:stratum 1} answer-counted-after-a-failed-save-test
  (let [monitor  (monitor-for "acme/app" {:generate-fn (constantly "An answer")})
        question {:category :question :comment (review-comment 9 "2026-10-01T00:00:00Z")}
        file     (str (store/repo-file "acme/app" "budget-42.edn"))]
    (state/update-budget! monitor 42 (state/get-or-create-budget monitor 42))
    (with-redefs [poller/post-comment (fn [& _] (dag/ok {:comment-posted true}))]
      (spit file "[]")
      (handlers/handle-question monitor {:pr/number 42} question)
      (fs/delete file)
      (handlers/handle-question monitor {:pr/number 42} question))
    (testing "an answer whose save failed is counted by the next save"
      (is (= 2 (:questions-answered (budget/load-budget "acme/app" 42)))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} damaged-budget-file-keeps-the-hard-stop-test
  (doseq [damage ["{:pr-number 42 :total-attempts" "{:pr-number 42}" "[]"]]
    (fs/delete-tree (fs/path test-home "state"))
    (let [monitor (monitor-for "acme/app")]
      (state/update-budget! monitor 42 (state/get-or-create-budget monitor 42))
      (let [file (file-named "budget-42.edn")]
        (spit file damage)
        (testing (str "budget file holding " damage)
          (testing "loads as exhausted, not as a fresh budget"
            (is (some? (budget/any-budget-exhausted?
                        (state/get-or-create-budget (monitor-for "acme/app") 42) nil))))
          (testing "is left as it was"
            (is (= damage (slurp file)))))))))

(deftest ^{:stratum 2} unreadable-watermarks-stop-every-action-test
  (run-cycle-with (monitor-for "acme/app") 7 [(review-comment 1 "2026-10-01T00:00:00Z")])
  (spit (file-named "watermarks.edn") "{7 {:last-seen-at")
  (let [bus               (events/create-event-bus)
        [result routed]   (run-cycle-with (monitor-for "acme/app" {:event-bus bus}) 7
                                          [(review-comment 1 "2026-10-01T00:00:00Z")
                                           (review-comment 2 "2026-10-05T00:00:00Z")])
        exhausted-events  (filter #(= :pr-monitor/budget-exhausted (:event/type %)) (:events @bus))]
    (testing "no comment is routed: the monitor cannot tell which were handled"
      (is (true? (:stopped? result)))
      (is (empty? routed)))
    (testing "the stop names the unreadable state"
      (is (re-find #"watermarks\.edn" (str (:load-error (first exhausted-events))))))))

(use-fixtures :each isolated-home)
