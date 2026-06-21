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

(ns ai.miniforge.cli.main.commands.etl-test
  "Unit tests for ETL CLI commands."
  (:require
   [ai.miniforge.repo-analyzer.interface :as repo-analyzer]
   [ai.miniforge.schema.interface :as schema]
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.cli.main.commands.etl :as sut]
   [ai.miniforge.cli.main.commands.shared :as shared]))

;------------------------------------------------------------------------------ Layer 0: Factory helpers

(defn make-etl-opts
  "Build a minimal ETL opts map for testing."
  ([]
   (make-etl-opts {}))
  ([overrides]
   (merge {:url "https://github.com/example/repo"} overrides)))

;------------------------------------------------------------------------------ Layer 1: Tests

(deftest validate-git-url-test
  (testing "HTTPS URL is valid"
    (is (true? (sut/validate-git-url "https://github.com/org/repo"))))

  (testing "SSH URL is valid"
    (is (true? (sut/validate-git-url "ssh://git@github.com/org/repo"))))

  (testing "git@ URL is valid"
    (is (true? (sut/validate-git-url "git@github.com:org/repo"))))

  (testing "HTTP URL is valid"
    (is (true? (sut/validate-git-url "http://github.com/org/repo"))))

  (testing "random string is invalid"
    (is (false? (sut/validate-git-url "not-a-url"))))

  (testing "empty string is invalid"
    (is (false? (sut/validate-git-url "")))))

(deftest etl-repo-cmd-missing-url-test
  (testing "command exits with error when no url provided"
    (let [exited? (atom false)]
      (with-redefs [shared/exit! (fn [_] (reset! exited? true))]
        (with-out-str (sut/etl-repo-cmd {}))
        (is @exited?)))))

(deftest etl-repo-cmd-invalid-url-test
  (testing "command exits with error for invalid URL"
    (let [exited? (atom false)]
      (with-redefs [shared/exit! (fn [_] (reset! exited? true))]
        (with-out-str (sut/etl-repo-cmd {:url "bad-url"}))
        (is @exited?)))))

(deftest etl-repo-cmd-analyzes-repo-directly-test
  (testing "valid repo URLs use the direct repo-analyzer interface"
    (let [repo-dir (.toFile
                    (java.nio.file.Files/createTempDirectory
                     "etl-repo-test"
                     (make-array java.nio.file.attribute.FileAttribute 0)))
          calls (atom [])]
      (.deleteOnExit repo-dir)
      (with-redefs-fn {#'sut/git-clone-temp
                       (fn [url]
                         (swap! calls conj [:clone url])
                         (schema/success :path (.getAbsolutePath repo-dir)))
                       #'repo-analyzer/analyze-repo
                       (fn [repo-path]
                         (swap! calls conj [:analyze repo-path])
                         {:technologies [:clojure]
                          :git-host "github"
                          :packs [:foundations]})
                    #'shared/call-optional-provider
                    (fn [& _]
                      (throw (ex-info "optional provider should not be used" {})))
                    #'shared/exit!
                    (fn [code]
                      (throw (ex-info "unexpected exit" {:code code})))}
        (fn []
          (with-out-str
            (sut/etl-repo-cmd {:url "https://github.com/example/repo"}))
          (is (= [[:clone "https://github.com/example/repo"]
                  [:analyze (.getAbsolutePath repo-dir)]]
                 @calls)))))))

(deftest etl-repo-cmd-exits-on-clone-failure-test
  (testing "clone failures become a non-zero command exit"
    (let [exit-code (atom nil)]
      (with-redefs-fn {#'sut/git-clone-temp
                       (fn [_url]
                         (schema/failure :path "clone failed"))
                       #'shared/exit!
                       (fn [code]
                         (reset! exit-code code))}
        (fn []
          (with-out-str
            (sut/etl-repo-cmd {:url "https://github.com/example/repo"}))
          (is (= 1 @exit-code)))))))

(deftest etl-repo-cmd-exits-on-analysis-failure-test
  (testing "repo-analyzer failures become a non-zero command exit"
    (let [repo-dir (.toFile
                    (java.nio.file.Files/createTempDirectory
                     "etl-repo-test"
                     (make-array java.nio.file.attribute.FileAttribute 0)))
          exit-code (atom nil)]
      (.deleteOnExit repo-dir)
      (with-redefs-fn {#'sut/git-clone-temp
                       (fn [_url]
                         (schema/success :path (.getAbsolutePath repo-dir)))
                       #'repo-analyzer/analyze-repo
                       (fn [_repo-path]
                         (throw (ex-info "analysis failed" {})))
                       #'shared/exit!
                       (fn [code]
                         (reset! exit-code code))}
        (fn []
          (with-out-str
            (sut/etl-repo-cmd {:url "https://github.com/example/repo"}))
          (is (= 1 @exit-code)))))))
