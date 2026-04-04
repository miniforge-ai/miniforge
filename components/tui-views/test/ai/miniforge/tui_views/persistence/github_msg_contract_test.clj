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

(ns ai.miniforge.tui-views.persistence.github-msg-contract-test
  "Contract tests verifying the msg/pr-diff-fetched constructor
   and handle-fetch-pr-diff produce messages that conform to the
   expected shape for downstream update/reducer consumption.

   These tests ensure the contract between the persistence layer
   and the Elm update loop is maintained."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.tui-views.msg :as msg]
   [ai.miniforge.tui-views.interface :as iface]
   [ai.miniforge.tui-views.persistence.github :as github]))

;; ---------------------------------------------------------------------------- msg/pr-diff-fetched contract

(deftest pr-diff-fetched-is-two-element-vector-test
  (testing "all arities produce [keyword map]"
    ;; success case
    (let [m (msg/pr-diff-fetched ["r" 1] "diff" {:title "T"} nil)]
      (is (vector? m))
      (is (= 2 (count m)))
      (is (keyword? (first m)))
      (is (map? (second m))))
    ;; error case
    (let [m (msg/pr-diff-fetched ["r" 1] nil nil "err")]
      (is (vector? m))
      (is (= 2 (count m)))
      (is (keyword? (first m)))
      (is (map? (second m))))))

(deftest pr-diff-fetched-payload-keys-contract-test
  (testing "payload always has :pr-id, :diff, :detail; :error only when present"
    ;; No error
    (let [[_ p] (msg/pr-diff-fetched ["r" 1] "d" {:t 1} nil)]
      (is (contains? p :pr-id))
      (is (contains? p :diff))
      (is (contains? p :detail))
      (is (not (contains? p :error))))
    ;; With error
    (let [[_ p] (msg/pr-diff-fetched ["r" 1] nil nil "bad")]
      (is (contains? p :pr-id))
      (is (contains? p :diff))
      (is (contains? p :detail))
      (is (contains? p :error)))))

(deftest pr-diff-fetched-error-false-string-test
  (testing "empty string error is truthy, so :error key IS included"
    ;; Note: empty string is truthy in Clojure (only nil and false are falsy)
    (let [[_ p] (msg/pr-diff-fetched ["r" 1] nil nil "")]
      (is (contains? p :error))
      (is (= "" (:error p))))))

(deftest pr-diff-fetched-error-false-value-test
  (testing "false as error is falsy, so :error key is omitted"
    (let [[_ p] (msg/pr-diff-fetched ["r" 1] nil nil false)]
      (is (not (contains? p :error))))))

;; ---------------------------------------------------------------------------- handle-fetch-pr-diff output contract

(deftest handle-fetch-pr-diff-output-is-msg-vector-test
  (testing "handle-fetch-pr-diff always returns [msg-type payload] vector"
    ;; Success
    (with-redefs [github/fetch-pr-diff-and-detail
                  (fn [_ _] {:diff "d" :detail {:t 1} :repo "r" :number 1})]
      (let [result (iface/handle-fetch-pr-diff {:repo "r" :number 1})]
        (is (vector? result))
        (is (= 2 (count result)))
        (is (= :msg/pr-diff-fetched (first result)))))
    ;; Exception
    (with-redefs [github/fetch-pr-diff-and-detail
                  (fn [_ _] (throw (Exception. "boom")))]
      (let [result (iface/handle-fetch-pr-diff {:repo "r" :number 1})]
        (is (vector? result))
        (is (= 2 (count result)))
        (is (= :msg/pr-diff-fetched (first result)))))))

(deftest handle-fetch-pr-diff-pr-id-is-vector-pair-test
  (testing ":pr-id is always [repo-string number-long]"
    (with-redefs [github/fetch-pr-diff-and-detail
                  (fn [_ _] {:diff nil :detail nil :repo "org/lib" :number 99})]
      (let [[_ payload] (iface/handle-fetch-pr-diff {:repo "org/lib" :number 99})]
        (is (vector? (:pr-id payload)))
        (is (= 2 (count (:pr-id payload))))
        (is (string? (first (:pr-id payload))))
        (is (integer? (second (:pr-id payload))))))))

(deftest handle-fetch-pr-diff-pr-id-from-string-number-test
  (testing ":pr-id number component is long even when input is string"
    (with-redefs [github/fetch-pr-diff-and-detail
                  (fn [_ _] {:diff nil :detail nil :repo "r" :number 42})]
      (let [[_ payload] (iface/handle-fetch-pr-diff {:repo "r" :number "42"})]
        (is (= ["r" 42] (:pr-id payload)))
        (is (integer? (second (:pr-id payload))))))))

(deftest handle-fetch-pr-diff-error-only-on-total-failure-test
  (testing "error is set only when BOTH diff and detail are nil"
    ;; Both nil → error
    (with-redefs [github/fetch-pr-diff-and-detail
                  (fn [_ _] {:diff nil :detail nil :repo "r" :number 1})]
      (let [[_ p] (iface/handle-fetch-pr-diff {:repo "r" :number 1})]
        (is (some? (:error p)))))
    ;; Diff present → no error
    (with-redefs [github/fetch-pr-diff-and-detail
                  (fn [_ _] {:diff "x" :detail nil :repo "r" :number 1})]
      (let [[_ p] (iface/handle-fetch-pr-diff {:repo "r" :number 1})]
        (is (nil? (:error p)))))
    ;; Detail present → no error
    (with-redefs [github/fetch-pr-diff-and-detail
                  (fn [_ _] {:diff nil :detail {:t 1} :repo "r" :number 1})]
      (let [[_ p] (iface/handle-fetch-pr-diff {:repo "r" :number 1})]
        (is (nil? (:error p)))))
    ;; Both present → no error
    (with-redefs [github/fetch-pr-diff-and-detail
                  (fn [_ _] {:diff "d" :detail {:t 1} :repo "r" :number 1})]
      (let [[_ p] (iface/handle-fetch-pr-diff {:repo "r" :number 1})]
        (is (nil? (:error p)))))))
