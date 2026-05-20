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

(ns ai.miniforge.phase-software-factory.phase-terminal-test
  "Unit tests for derive-termination-reason in phase-terminal.

   All inputs are plain data maps — no live state, no event streams.
   Tests cover every branch of the priority-ordered cond."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.miniforge.phase-software-factory.phase-terminal :as sut]))

;; ---------------------------------------------------------------------------
;; Helpers

(defn reason [result & [watchdog]]
  (:phase/termination-reason
   (if watchdog
     (sut/derive-termination-reason result watchdog)
     (sut/derive-termination-reason result))))

;; ---------------------------------------------------------------------------
;; Happy path — normal termination

(deftest normal-success
  (testing "success result with no watchdog yields :normal"
    (let [result {:status :success :output {} :metrics {:tokens 100}}]
      (is (= :normal (reason result)))))

  (testing "nil result yields :normal"
    (is (= :normal (reason nil))))

  (testing "empty result yields :normal"
    (is (= :normal (reason {}))))

  (testing "arity-1 call (no watchdog arg) yields :normal"
    (is (= :normal
           (:phase/termination-reason
            (sut/derive-termination-reason {:status :success}))))))

;; ---------------------------------------------------------------------------
;; Watchdog stall → :agent-stalled

(deftest watchdog-stall-by-flag
  (testing ":stalled? true produces :agent-stalled"
    (let [result {:status :error :error {:message "silent"}}]
      (is (= :agent-stalled (reason result {:stalled? true})))))

  (testing ":stalled? false with no gap-ms is not a stall"
    (let [result {:status :error}]
      (is (= :normal (reason result {:stalled? false})))))

  (testing "nil watchdog state is not a stall"
    (is (= :normal (reason {} nil)))))

(deftest watchdog-stall-by-gap-ms
  (testing "positive :stall/gap-duration-ms produces :agent-stalled"
    (let [result {:status :error}
          ws     {:stall/gap-duration-ms 45000}]
      (is (= :agent-stalled (reason result ws)))))

  (testing "gap-ms is included in the returned map"
    (let [result {:status :error}
          ws     {:stall/gap-duration-ms 30000}
          out    (sut/derive-termination-reason result ws)]
      (is (= :agent-stalled (:phase/termination-reason out)))
      (is (= 30000 (:stall/gap-duration-ms out)))))

  (testing "zero gap-ms is NOT a stall"
    (let [result {:status :error}]
      (is (= :normal (reason result {:stall/gap-duration-ms 0})))))

  (testing "stall from :stalled? true carries gap-ms when both present"
    (let [out (sut/derive-termination-reason
               {:status :error}
               {:stalled? true :stall/gap-duration-ms 12000})]
      (is (= :agent-stalled (:phase/termination-reason out)))
      (is (= 12000 (:stall/gap-duration-ms out))))))

;; ---------------------------------------------------------------------------
;; Curator rejection → :curator-rejected

(deftest curator-rejected-no-files-written
  (testing ":curator/no-files-written error code maps to :curator-rejected"
    (let [result {:status  :error
                  :error   {:data {:code :curator/no-files-written}
                             :message "Curator: no files written"}}]
      (is (= :curator-rejected (reason result)))))

  (testing ":curator/no-files-written beats :rate-limited? hint (stall wins over curator)"
    ;; stall takes priority over curator-rejected
    (let [result {:status :error
                  :error  {:data {:code :curator/no-files-written}}}]
      (is (= :agent-stalled
             (reason result {:stalled? true}))))))

(deftest curator-rejected-release-zero-files
  (testing ":release/zero-files :data :type maps to :curator-rejected"
    (let [result {:status :error
                  :error  {:data {:type :release/zero-files}}}]
      (is (= :curator-rejected (reason result)))))

  (testing ":release/zero-files :data :code also maps to :curator-rejected"
    (let [result {:status :error
                  :error  {:data {:code :release/zero-files}}}]
      (is (= :curator-rejected (reason result))))))

;; ---------------------------------------------------------------------------
;; Tool / infra error → :tool-error

(deftest tool-error-from-code
  (testing ":tool-error error code maps to :tool-error"
    (let [result {:status :error
                  :error  {:data {:code :tool-error}
                            :message "MCP tool invocation failed"}}]
      (is (= :tool-error (reason result))))))

(deftest tool-error-from-rate-limit-hint
  (testing ":rate-limited? true hint produces :tool-error"
    (let [result {:status :error :error {:message "429 Too Many Requests"}}]
      (is (= :tool-error (reason result {:rate-limited? true})))))

  (testing ":rate-limited? falsy value is not a tool-error on its own"
    (let [result {:status :error}]
      (is (= :normal (reason result {:rate-limited? nil})))))

  (testing ":rate-limited? from re-find match (truthy but not true) is handled"
    ;; re-find returns a MatchResult (truthy), not literal true
    (let [match   (re-find #"429" "429 error")
          result  {:status :error}]
      (is (= :tool-error (reason result {:rate-limited? match}))))))

;; ---------------------------------------------------------------------------
;; Priority ordering

(deftest priority-ordering
  (testing "stall takes precedence over curator-rejected"
    (let [result {:status :error
                  :error  {:data {:code :curator/no-files-written}}}]
      (is (= :agent-stalled
             (reason result {:stalled? true})))))

  (testing "stall takes precedence over tool-error"
    (let [result {:status :error
                  :error  {:data {:code :tool-error}}}]
      (is (= :agent-stalled
             (reason result {:stalled? true :stall/gap-duration-ms 5000})))))

  (testing "curator-rejected takes precedence over rate-limit hint"
    (let [result {:status :error
                  :error  {:data {:code :curator/no-files-written}}}]
      (is (= :curator-rejected
             (reason result {:rate-limited? true}))))))
