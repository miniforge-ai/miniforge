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
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.config.profile :as profile]))

;------------------------------------------------------------------------------ Layer 0
;; validate-profile tests

(deftest validate-profile-valid-test
  (testing "Valid profile passes validation"
    (let [p {:tokens {:github "tok-123" :gitlab "tok-456"}
             :identity {:name "Test User" :email "test@example.com"}
             :defaults {:agent :claude-code}}
          result (profile/validate-profile p)]
      (is (:valid? result))
      (is (empty? (:errors result))))))

(deftest validate-profile-empty-test
  (testing "Empty profile is valid (all fields optional)"
    (let [result (profile/validate-profile {})]
      (is (:valid? result)))))

(deftest validate-profile-bad-tokens-test
  (testing "Non-map :tokens fails"
    (let [result (profile/validate-profile {:tokens "not-a-map"})]
      (is (not (:valid? result)))
      (is (some #(re-find #":tokens" %) (:errors result))))))

(deftest validate-profile-bad-identity-test
  (testing "Non-map :identity fails"
    (let [result (profile/validate-profile {:identity "not-a-map"})]
      (is (not (:valid? result))))))

(deftest validate-profile-bad-email-test
  (testing "Non-string email fails"
    (let [result (profile/validate-profile {:identity {:email 123}})]
      (is (not (:valid? result))))))

;------------------------------------------------------------------------------ Layer 1
;; load-profile tests

(deftest load-profile-missing-file-test
  (testing "Missing file returns nil"
    (is (nil? (profile/load-profile "/tmp/nonexistent-miniforge-profile.edn")))))

(deftest load-profile-roundtrip-test
  (testing "Aero reads profile with #env tags"
    (let [tmp (java.io.File/createTempFile "profile-test" ".edn")]
      (try
        (spit tmp "{:tokens {:github \"test-token\"}\n :identity {:name \"Chris\" :email \"c@m.ai\"}}")
        (let [p (profile/load-profile (.getPath tmp))]
          (is (= "test-token" (get-in p [:tokens :github])))
          (is (= "Chris" (get-in p [:identity :name]))))
        (finally
          (.delete tmp))))))

;------------------------------------------------------------------------------ Layer 2
;; resolve-token tests

(deftest resolve-token-from-profile-test
  (testing "Token resolved from profile when no env override"
    (let [profile {:tokens {:github "profile-gh-token"
                            :gitlab "profile-gl-token"}}]
      ;; Profile token should be returned when no MINIFORGE_GIT_TOKEN env
      ;; (We can't unset env vars in tests, but we can pass profile directly)
      (is (= "profile-gh-token"
             (profile/resolve-token :github {:profile profile})))
      (is (= "profile-gl-token"
             (profile/resolve-token :gitlab {:profile profile}))))))

(deftest resolve-token-nil-for-unknown-host-test
  (testing "Unknown host kind with no profile returns nil"
    (let [profile {:tokens {}}]
      (is (nil? (profile/resolve-token :custom {:profile profile}))))))

(deftest resolve-token-profile-nil-test
  (testing "Nil profile with no env vars falls through"
    ;; With empty profile and no matching env var, should return nil for :custom
    (is (nil? (profile/resolve-token :custom {:profile {}})))))
