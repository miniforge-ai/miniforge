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
(ns ai.miniforge.pr-lifecycle.monitor-store-test
  "Repo-slug paths, merge rules and write refusals for PR monitor state."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.config.interface :as mf-config]
   [ai.miniforge.pr-lifecycle.monitor-budget :as budget]
   [ai.miniforge.pr-lifecycle.monitor-store :as sut]
   [ai.miniforge.pr-lifecycle.pr-poller :as poller]
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing use-fixtures]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} test-home
  "One temporary miniforge home for this namespace, emptied before each test."
  (str (fs/create-temp-dir {:prefix "mf-pr-monitor-store-"})))

(deftest ^{:stratum 0} merge-watermarks-test
  (testing "per PR the later watermark wins, and PRs from both sides survive"
    (let [wm (fn [ts] {:last-seen-at ts :advanced-at ts})]
      (is (= {1 (wm "2026-10-03") 2 (wm "2026-10-02") 3 (wm "2026-10-01")}
             (poller/merge-watermarks {1 (wm "2026-10-03") 2 (wm "2026-10-01") 3 (wm "2026-10-01")}
                                      {1 (wm "2026-10-02") 2 (wm "2026-10-02")}))))))

(deftest ^{:stratum 0} merge-budgets-test
  (let [earlier (java.util.Date. 1000)
        on-disk (assoc (budget/create-budget 42) :comment-attempts {1 2 2 1} :total-attempts 3
                       :questions-answered 4 :fixes-pushed 1 :started-at earlier)
        merged  (budget/merge-budgets on-disk (assoc (budget/create-budget 42)
                                                     :comment-attempts {1 1 3 1} :total-attempts 2))]
    (testing "no counter goes below what is already on disk"
      (is (= [{1 2 2 1 3 1} 3 4 1]
             ((juxt :comment-attempts :total-attempts :questions-answered :fixes-pushed) merged))))
    (testing "the earlier start time is kept, so the time limit is not extended"
      (is (= earlier (:started-at merged))))))

(deftest ^{:stratum 0} save-budget-refusals-test
  (testing "a stand-in for an unreadable budget is never written"
    (let [held (budget/exhausted-budget 42 "unreadable")]
      (is (anomaly/anomaly? (budget/save-budget! "acme/app" held)))
      (is (not (fs/exists? (sut/repo-file "acme/app" "budget-42.edn"))))))
  (testing "a file holding something else is not overwritten"
    (let [file (sut/repo-file "acme/app" "budget-7.edn")]
      (fs/create-dirs (fs/parent file))
      (spit file "{:pr-number 99}")
      (is (anomaly/anomaly? (budget/save-budget! "acme/app" (budget/create-budget 7))))
      (is (= "{:pr-number 99}" (slurp file)))))
  (testing "without an owner/name slug nothing is written and the load is exhausted"
    (is (anomaly/anomaly? (budget/save-budget! nil (budget/create-budget 7))))
    (is (some? (budget/any-budget-exhausted? (budget/load-budget nil 7) nil)))))

(deftest ^{:stratum 0} save-watermarks-test
  (let [wm (fn [pr ts] {:repo-slug "acme/app" :prs {pr {:last-seen-at ts :advanced-at ts}}})]
    (testing "the save returns the merged watermarks, including PRs saved by others"
      (poller/save-watermarks! (wm 1 "2026-10-01"))
      (is (= #{1 2} (set (keys (:prs (poller/save-watermarks! (wm 2 "2026-10-02")))))))
      (is (= #{1 2} (set (keys (:prs (poller/load-watermarks "acme/app")))))))
    (testing "a file holding something else is reported and not overwritten"
      (let [file (sut/repo-file "acme/app" "watermarks.edn")]
        (spit file "{\"7\" :seen}")
        (is (anomaly/anomaly? (poller/load-watermarks "acme/app")))
        (is (anomaly/anomaly? (poller/save-watermarks! (wm 3 "2026-10-03"))))
        (is (= "{\"7\" :seen}" (slurp file)))))))

(deftest ^{:stratum 0} file-emptied-after-load-is-not-overwritten-test
  (doseq [damage ["" ";; truncated\n" "nil"]]
    (testing (str "a state file holding " (pr-str damage))
      (let [budget-file (str (sut/repo-file "acme/app" "budget-7.edn"))
            wm-file     (str (sut/repo-file "acme/app" "watermarks.edn"))
            watermarks  {:repo-slug "acme/app" :prs {1 {:last-seen-at "2026-10-01" :advanced-at "a"}}}]
        (budget/save-budget! "acme/app" (budget/create-budget 7))
        (poller/save-watermarks! watermarks)
        (spit budget-file damage)
        (spit wm-file damage)
        (is (anomaly/anomaly? (budget/save-budget! "acme/app" (budget/create-budget 7))))
        (is (anomaly/anomaly? (poller/save-watermarks! watermarks)))
        (is (= [damage damage] [(slurp budget-file) (slurp wm-file)]))))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} isolated-home
  [run-test]
  (fs/delete-tree (fs/path test-home "state"))
  (with-redefs [mf-config/miniforge-home (constantly test-home)]
    (run-test)))

(deftest ^{:stratum 1} repo-file-test
  (testing "an owner/name slug gets its own directory under the miniforge home"
    (is (= (str test-home "/state/pr-monitor/repos/acme/app/watermarks.edn")
           (str (sut/repo-file "acme/app" "watermarks.edn"))
           (str (sut/repo-file "Acme/App" "watermarks.edn")))))
  (testing "anything else gets no file"
    (doseq [slug [nil "" "acme" "acme/app/extra" "../app" "acme/.." "./app" "a b/c"]]
      (is (nil? (sut/repo-file slug "watermarks.edn")) (pr-str slug)))))

(deftest ^{:stratum 1} read-map-test
  (let [file (str (fs/path test-home "state" "f.edn"))]
    (fs/create-dirs (fs/parent file))
    (is (nil? (sut/read-map file)) "absent file")
    (doseq [text ["" "{:a 1} {:b 2}" "[1 2]" "{:a"]]
      (spit file text)
      (is (anomaly/anomaly? (sut/read-map file)) (pr-str text)))
    (spit file "{:a 1}")
    (is (= {:a 1} (sut/read-map file)))))

(use-fixtures :each isolated-home)
