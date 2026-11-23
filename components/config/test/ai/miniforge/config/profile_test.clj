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
(ns ai.miniforge.config.profile-test
  "Tests for user profile loading and token resolution."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.config.profile :as profile]))

;------------------------------------------------------------------------------ Layer 0

;; validate-profile tests
(deftest ^{:stratum 0} validate-profile-valid-test
  (testing "Valid profile passes validation"
    (let [p {:tokens {:github "tok-123" :gitlab "tok-456"}
             :identity {:name "Test User" :email "test@example.com"}
             :defaults {:agent :claude-code}}
          result (profile/validate-profile p)]
      (is (:valid? result))
      (is (empty? (:errors result))))))

(deftest ^{:stratum 0} validate-profile-empty-test
  (testing "Empty profile is valid (all fields optional)"
    (let [result (profile/validate-profile {})]
      (is (:valid? result)))))

(deftest ^{:stratum 0} validate-profile-bad-tokens-test
  (testing "Non-map :tokens fails"
    (let [result (profile/validate-profile {:tokens "not-a-map"})]
      (is (not (:valid? result)))
      (is (some #(re-find #":tokens" %) (:errors result))))))

(deftest ^{:stratum 0} validate-profile-bad-identity-test
  (testing "Non-map :identity fails"
    (let [result (profile/validate-profile {:identity "not-a-map"})]
      (is (not (:valid? result))))))

(deftest ^{:stratum 0} validate-profile-bad-email-test
  (testing "Non-string email fails"
    (let [result (profile/validate-profile {:identity {:email 123}})]
      (is (not (:valid? result))))))

;; load-profile tests
(deftest ^{:stratum 0} load-profile-missing-file-test
  (testing "Missing file returns nil"
    (is (nil? (profile/load-profile "/tmp/nonexistent-miniforge-profile.edn")))))

(deftest ^{:stratum 0} load-profile-roundtrip-test
  (testing "Aero reads profile with #env tags"
    (let [tmp (java.io.File/createTempFile "profile-test" ".edn")]
      (try
        (spit tmp "{:tokens {:github \"test-token\"}\n :identity {:name \"Chris\" :email \"c@m.ai\"}}")
        (let [p (profile/load-profile (.getPath tmp))]
          (is (= "test-token" (get-in p [:tokens :github])))
          (is (= "Chris" (get-in p [:identity :name]))))
        (finally
          (.delete tmp))))))

;; resolve-token tests
(deftest ^{:stratum 0} resolve-token-from-profile-test
  (testing "Token resolved from profile when no env override"
    (let [profile {:tokens {:github "profile-gh-token"
                            :gitlab "profile-gl-token"}}]
      ;; Profile token should be returned when no MINIFORGE_GIT_TOKEN env
      ;; (We can't unset env vars in tests, but we can pass profile directly)
      (is (= "profile-gh-token"
             (profile/resolve-token :github {:profile profile})))
      (is (= "profile-gl-token"
             (profile/resolve-token :gitlab {:profile profile}))))))

(deftest ^{:stratum 0} resolve-token-nil-for-unknown-host-test
  (testing "Unknown host kind with no profile returns nil"
    (let [profile {:tokens {}}]
      (is (nil? (profile/resolve-token :custom {:profile profile}))))))

(deftest ^{:stratum 0} resolve-token-profile-nil-test
  (testing "Nil profile with no env vars falls through"
    ;; With empty profile and no matching env var, should return nil for :custom
    (is (nil? (profile/resolve-token :custom {:profile {}})))))

(deftest ^{:stratum 0} gh-cli-token-gives-up-after-its-timeout-test
  (testing "a gh that stalls is killed at the timeout and yields no token,
            rather than blocking the lookup"
    (let [gh-cli-token #'profile/gh-cli-token
          started      (System/currentTimeMillis)]
      (is (= :timeout (gh-cli-token ["sleep" "10"] 200)))
      (is (< (- (System/currentTimeMillis) started) 5000)))))

(deftest ^{:stratum 0} gh-cli-token-bounds-the-stdout-read-test
  (testing "a gh that exits while a child keeps its stdout open cannot hold
            the read past the deadline"
    (let [started (System/currentTimeMillis)]
      (is (= :timeout (#'profile/gh-cli-token ["sh" "-c" "sleep 3 & sleep 0.2; exit 0"] 1000)))
      (is (< (- (System/currentTimeMillis) started) 2500))))
  (testing "a gh that fails is no token at once, without waiting on the
            child holding its stdout"
    (let [started (System/currentTimeMillis)]
      (is (nil? (#'profile/gh-cli-token ["sh" "-c" "sleep 3 & sleep 0.2; exit 1"] 2000)))
      (is (< (- (System/currentTimeMillis) started) 1500)))))

(deftest ^{:stratum 0} gh-cli-token-interrupt-kills-gh-and-keeps-the-flag-test
  (testing "an interrupted lookup kills gh's process tree, returns
            :interrupted and leaves the thread's interrupt flag set"
    (let [pid-file (java.io.File/createTempFile "gh-child" ".pid")
          outcome  (promise)
          worker   (Thread. #(deliver outcome
                                      [(#'profile/gh-cli-token
                                        ["sh" "-c" (str "sleep 30 & echo $! > " pid-file "; wait")]
                                        30000)
                                       (.isInterrupted (Thread/currentThread))]))]
      (.start worker)
      (while (str/blank? (slurp pid-file)) (Thread/sleep 20))
      (.interrupt worker)
      (let [[r flag] (deref outcome 5000 [::hung nil])
            child    (java.lang.ProcessHandle/of (parse-long (str/trim (slurp pid-file))))
            deadline (+ (System/currentTimeMillis) 5000)]
        (while (and (some-> child (.orElse nil) .isAlive)
                    (< (System/currentTimeMillis) deadline))
          (Thread/sleep 50))
        (is (= :interrupted r))
        (is (true? flag))
        (is (not (some-> child (.orElse nil) .isAlive))))
      (.delete pid-file))))

(deftest ^{:stratum 0} gh-cli-token-timeout-kills-the-process-tree-test
  (testing "a child the stalled gh started is killed too, not left orphaned"
    (let [pid-file (java.io.File/createTempFile "gh-child" ".pid")
          _        (#'profile/gh-cli-token
                    ["sh" "-c" (str "sleep 30 & echo $! > " pid-file "; wait")] 500)
          child    (java.lang.ProcessHandle/of (parse-long (str/trim (slurp pid-file))))
          deadline (+ (System/currentTimeMillis) 5000)]
      (while (and (some-> child (.orElse nil) .isAlive)
                  (< (System/currentTimeMillis) deadline))
        (Thread/sleep 50))
      (is (not (some-> child (.orElse nil) .isAlive)))
      (.delete pid-file))))

(deftest ^{:stratum 0} gh-cli-token-reads-the-token-test
  (testing "a gh that answers in time yields its trimmed output"
    (is (= "tok-from-gh" (#'profile/gh-cli-token ["echo" " tok-from-gh "] 5000)))))
