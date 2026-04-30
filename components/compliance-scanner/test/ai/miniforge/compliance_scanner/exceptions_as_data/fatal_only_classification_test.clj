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

(ns ai.miniforge.compliance-scanner.exceptions-as-data.fatal-only-classification-test
  "Programmer-error guards (`unknown` / `unsupported` / `required`)
   are classified as :fatal-only — informational, not actionable."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.compliance-scanner.exceptions-as-data :as exc]))

(deftest unknown-message-classified-fatal-only
  (testing "throw whose message contains 'Unknown' is :fatal-only"
    (let [src "(ns ai.miniforge.foo.core)
              (defn lookup [m k]
                (or (get m k)
                    (throw (ex-info (str \"Unknown agent role: \" k) {:k k}))))"
          {:keys [violations]} (exc/analyze-content "foo.clj" src)]
      (is (= 1 (count violations)))
      (is (= :fatal-only (:classification (first violations)))))))

(deftest unsupported-is-fatal-only
  (testing "'Unsupported X' messages are :fatal-only"
    (let [src "(ns ai.miniforge.foo.core)
              (defn dispatch [k]
                (case k
                  :a 1
                  (throw (ex-info (str \"Unsupported dispatch: \" k) {:k k}))))"
          {:keys [violations]} (exc/analyze-content "foo.clj" src)]
      (is (= 1 (count violations)))
      (is (= :fatal-only (:classification (first violations)))))))

(deftest required-config-is-fatal-only
  (testing "'X is required' programmer-error messages are :fatal-only"
    (let [src "(ns ai.miniforge.foo.core)
              (defn check [opts]
                (when-not (:bucket opts)
                  (throw (ex-info \":bucket required\" {}))))"
          {:keys [violations]} (exc/analyze-content "foo.clj" src)]
      (is (= 1 (count violations)))
      (is (= :fatal-only (:classification (first violations)))))))

(deftest plain-failure-is-cleanup-needed
  (testing "messages without programmer-error markers are :cleanup-needed"
    (let [src "(ns ai.miniforge.foo.core)
              (defn fetch! [url]
                (throw (ex-info \"GET request failed\" {:url url})))"
          {:keys [violations]} (exc/analyze-content "foo.clj" src)]
      (is (= 1 (count violations)))
      (is (= :cleanup-needed (:classification (first violations)))))))
