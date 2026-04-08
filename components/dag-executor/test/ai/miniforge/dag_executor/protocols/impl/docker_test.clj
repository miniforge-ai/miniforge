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

(ns ai.miniforge.dag-executor.protocols.impl.docker-test
  "Tests for Docker executor: token sanitization, URL auth, image management."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.dag-executor.protocols.impl.docker :as docker]))

;; Private fn accessor helper
(defn- private-fn [sym]
  (var-get (ns-resolve 'ai.miniforge.dag-executor.protocols.impl.docker sym)))

;; ============================================================================
;; sanitize-token
;; ============================================================================

(deftest sanitize-token-github-test
  (testing "sanitizes GitHub x-access-token"
    (let [sanitize (private-fn 'sanitize-token)]
      (is (= "https://x-access-token:***@github.com/o/r.git"
             (sanitize "https://x-access-token:ghp_abc123@github.com/o/r.git"))))))

(deftest sanitize-token-gitlab-test
  (testing "sanitizes GitLab oauth2 token"
    (let [sanitize (private-fn 'sanitize-token)]
      (is (= "https://oauth2:***@gitlab.com/g/p.git"
             (sanitize "https://oauth2:glpat-xyz@gitlab.com/g/p.git"))))))

(deftest sanitize-token-no-token-test
  (testing "passes through strings without tokens"
    (let [sanitize (private-fn 'sanitize-token)]
      (is (= "git clone https://github.com/o/r.git"
             (sanitize "git clone https://github.com/o/r.git"))))))

;; ============================================================================
;; authenticated-https-url
;; ============================================================================

(deftest authenticated-url-github-test
  (testing "injects GitHub x-access-token"
    (let [auth-url (private-fn 'authenticated-https-url)]
      (is (= "https://x-access-token:tok123@github.com/o/r.git"
             (auth-url "https://github.com/o/r.git" "tok123" :github))))))

(deftest authenticated-url-gitlab-test
  (testing "injects GitLab oauth2 token"
    (let [auth-url (private-fn 'authenticated-https-url)]
      (is (= "https://oauth2:tok456@gitlab.com/g/p.git"
             (auth-url "https://gitlab.com/g/p.git" "tok456" :gitlab))))))

;; ============================================================================
;; image-exists? (public)
;; ============================================================================

(deftest image-exists-nonexistent-test
  (testing "image-exists? returns false for nonexistent image"
    (is (false? (docker/image-exists? nil "nonexistent/image:never")))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.dag-executor.protocols.impl.docker-test)
  :leave-this-here)
