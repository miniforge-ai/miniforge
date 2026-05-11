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

(ns ai.miniforge.cli.anomaly.etl-anomaly-test
  "Coverage for `cli.main.commands.etl` boundary escalation via
   `response/throw-anomaly!`.

   Pre-cleanup, each site was a raw `(throw (ex-info ...))`. Post-
   cleanup, throws route through the canonical
   `response/throw-anomaly!` so CLI argument-validation failures
   surface as typed anomaly categories instead of opaque ex-info."
  (:require
   [clojure.test :refer [deftest is testing]]
   [babashka.fs :as fs]
   [ai.miniforge.cli.main.commands.etl :as etl])
  (:import
   (clojure.lang ExceptionInfo)))

;------------------------------------------------------------------------------ resolve-pipeline-path (private)

(deftest resolve-pipeline-path-empty-dir-throws-not-found
  (testing "empty pack dir with no pipelines/*.edn raises :anomalies/not-found"
    (let [tmp (fs/create-temp-dir {:prefix "cli-etl-test-"})]
      (try
        (let [thrown (try (@#'etl/resolve-pipeline-path (str tmp))
                          nil
                          (catch ExceptionInfo e e))]
          (is (some? thrown))
          (is (re-find #"Could not find a single pipelines/\*\.edn"
                       (.getMessage thrown)))
          (is (= :anomalies/not-found
                 (:anomaly/category (ex-data thrown))))
          (is (= (str (fs/absolutize tmp))
                 (:pack-dir (ex-data thrown)))))
        (finally
          (fs/delete-tree tmp))))))

(deftest resolve-pipeline-path-nonexistent-path-throws-incorrect
  (testing "non-dir non-edn path raises :anomalies/incorrect"
    (let [thrown (try (@#'etl/resolve-pipeline-path "/no/such/thing")
                      nil
                      (catch ExceptionInfo e e))]
      (is (some? thrown))
      (is (re-find #"Not a pack directory or pipeline EDN" (.getMessage thrown)))
      (is (= :anomalies/incorrect (:anomaly/category (ex-data thrown))))
      (is (= "/no/such/thing" (:input (ex-data thrown)))))))

(deftest resolve-pipeline-path-valid-edn-returns-path
  (testing "direct pipeline EDN file returns [nil <abs-path>]"
    (let [tmp (fs/create-temp-file {:suffix ".edn"})]
      (try
        (let [result (@#'etl/resolve-pipeline-path (str tmp))]
          (is (= [nil (str (fs/absolutize tmp))] result)))
        (finally
          (fs/delete-if-exists tmp))))))

;------------------------------------------------------------------------------ resolve-env-path (private)

(deftest resolve-env-path-nil-throws-incorrect
  (testing "nil env raises :anomalies/incorrect"
    (let [thrown (try (@#'etl/resolve-env-path nil nil)
                      nil
                      (catch ExceptionInfo e e))]
      (is (some? thrown))
      (is (re-find #"missing --env" (.getMessage thrown)))
      (is (= :anomalies/incorrect (:anomaly/category (ex-data thrown)))))))

(deftest resolve-env-path-name-without-pack-dir-throws-incorrect
  (testing "env name without pack-dir raises :anomalies/incorrect"
    (let [thrown (try (@#'etl/resolve-env-path "prod" nil)
                      nil
                      (catch ExceptionInfo e e))]
      (is (some? thrown))
      (is (re-find #"pass a \.edn path instead" (.getMessage thrown)))
      (is (= :anomalies/incorrect (:anomaly/category (ex-data thrown))))
      (is (= "prod" (:env (ex-data thrown)))))))

(deftest resolve-env-path-name-missing-in-pack-throws-not-found
  (testing "env name not found under pack-dir/envs raises :anomalies/not-found"
    (let [tmp (fs/create-temp-dir {:prefix "cli-etl-pack-"})]
      (try
        (let [thrown (try (@#'etl/resolve-env-path "prod" (str tmp))
                          nil
                          (catch ExceptionInfo e e))]
          (is (some? thrown))
          (is (re-find #"env not found" (.getMessage thrown)))
          (is (= :anomalies/not-found (:anomaly/category (ex-data thrown))))
          (is (= "prod" (:env (ex-data thrown)))))
        (finally
          (fs/delete-tree tmp))))))

(deftest resolve-env-path-valid-edn-returns-abs
  (testing "env .edn path returns absolutized path"
    (let [tmp (fs/create-temp-file {:suffix ".edn"})]
      (try
        (let [result (@#'etl/resolve-env-path (str tmp) nil)]
          (is (= (str (fs/absolutize tmp)) result)))
        (finally
          (fs/delete-if-exists tmp))))))
