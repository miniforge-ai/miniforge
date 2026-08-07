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
(ns ai.miniforge.pr-lifecycle.github-conversation-test
  "Behavior locks for the decomposed GitHub conversation workflow."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.pr-lifecycle.github :as github]
   [ai.miniforge.pr-lifecycle.github-conversation :as conversation]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} reply-success []
  (dag/ok {:url "https://github.test/reply/1"}))

(defn- ^{:stratum 0} thread-success [resolved?]
  (dag/ok {:thread-id "PRRT_test" :is-resolved resolved?}))

(deftest ^{:stratum 0} link-stops-when-reply-fails-test
  (let [failure (dag/err :gh-command-failed "reply failed")]
    (with-redefs [github/reply-to-comment (fn [& _] failure)
                  github/get-thread-id
                  (fn [& _] (throw (ex-info "must not resolve" {})))]
      (let [result (conversation/link-fix-pr-to-comment
                    "/repo" 42 7 99 nil)]
        (is (dag/err? result))
        (is (= :reply-failed (get-in result [:error :code])))
        (is (string? (get-in result [:error :message])))
        (is (= (:error failure) (get-in result [:error :data :cause])))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} link-can-preserve-an-unresolved-reply-test
  (with-redefs [github/reply-to-comment (fn [& _] (reply-success))
                github/get-thread-id
                (fn [& _] (throw (ex-info "auto-resolution disabled" {})))]
    (let [result (conversation/link-fix-pr-to-comment
                  "/repo" 42 7 99 nil :auto-resolve false)]
      (is (= {:reply-posted true
              :resolved false
              :reply-url "https://github.test/reply/1"}
             (:data result))))))

(deftest ^{:stratum 1} link-preserves-reply-when-thread-lookup-fails-test
  (let [failure (dag/err :thread-not-found "review thread was not found")]
    (with-redefs [github/reply-to-comment (fn [& _] (reply-success))
                  github/get-thread-id (fn [& _] failure)]
      (let [result (conversation/link-fix-pr-to-comment "/repo" 42 7 99 nil)]
        (is (dag/ok? result))
        (is (true? (get-in result [:data :reply-posted])))
        (is (false? (get-in result [:data :resolved])))
        (is (= (:error failure)
               (get-in result [:data :resolution-error])))))))

(deftest ^{:stratum 1} link-resolves-an-open-thread-test
  (let [reply-message (atom nil)]
    (with-redefs [github/reply-to-comment
                  (fn [_ _ _ message]
                    (reset! reply-message message)
                    (reply-success))
                  github/get-thread-id (fn [& _] (thread-success false))
                  github/resolve-conversation
                  (fn [_ thread-id]
                    (dag/ok {:thread-id thread-id :resolved true}))]
      (let [result (conversation/link-fix-pr-to-comment
                    "/repo" 42 7 99 nil)]
        (testing "the reply links the exact fix PR"
          (is (= "Fixed in PR #99" @reply-message)))
        (is (= {:reply-posted true
                :resolved true
                :reply-url "https://github.test/reply/1"
                :thread-id "PRRT_test"}
               (:data result)))))))

(deftest ^{:stratum 1} link-does-not-resolve-an-already-resolved-thread-test
  (with-redefs [github/reply-to-comment (fn [& _] (reply-success))
                github/get-thread-id (fn [& _] (thread-success true))
                github/resolve-conversation
                (fn [& _] (throw (ex-info "already resolved" {})))]
    (let [result (conversation/link-fix-pr-to-comment "/repo" 42 7 99 nil)]
      (is (true? (get-in result [:data :resolved])))
      (is (true? (get-in result [:data :already-resolved]))))))

(deftest ^{:stratum 1} link-preserves-reply-when-resolution-fails-test
  (let [failure (dag/err :resolution-failed "review thread was not resolved")]
    (with-redefs [github/reply-to-comment (fn [& _] (reply-success))
                  github/get-thread-id (fn [& _] (thread-success false))
                  github/resolve-conversation (fn [& _] failure)]
      (let [result (conversation/link-fix-pr-to-comment "/repo" 42 7 99 nil)]
        (is (dag/ok? result))
        (is (true? (get-in result [:data :reply-posted])))
        (is (false? (get-in result [:data :resolved])))
        (is (= "PRRT_test" (get-in result [:data :thread-id])))
        (is (= (:error failure)
               (get-in result [:data :resolution-error])))))))
