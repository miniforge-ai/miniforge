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

(ns ai.miniforge.compliance-scanner.exceptions-as-data.positive-detection-test
  "Positive detection: throws inside non-boundary namespaces are flagged."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.compliance-scanner.exceptions-as-data :as exc]))

(deftest detects-bare-throw-in-non-boundary-namespace
  (testing "(throw ex-info ...) inside a defn yields one violation"
    (let [src "(ns ai.miniforge.foo.core)
              (defn boom []
                (throw (ex-info \"boom\" {:reason :unspecified})))"
          {:keys [ns boundary? violations]} (exc/analyze-content "foo.clj" src)]
      (is (= 'ai.miniforge.foo.core ns))
      (is (false? boundary?))
      (is (= 1 (count violations)))
      (let [v (first violations)]
        (is (= :throw (:kind v)))
        (is (= :cleanup-needed (:classification v)))
        (is (pos-int? (:line v)))))))

(deftest detects-ex-info-without-throw
  (testing "bare (ex-info ...) is flagged even without an enclosing throw"
    (let [src "(ns ai.miniforge.foo.core)
              (defn make-err [] (ex-info \"oops\" {}))"
          {:keys [violations]} (exc/analyze-content "foo.clj" src)]
      (is (= 1 (count violations)))
      (is (= :ex-info (:kind (first violations)))))))

(deftest detects-throw-anomaly!-bang
  (testing "namespaced response/throw-anomaly! is flagged as throw"
    (let [src "(ns ai.miniforge.foo.core
                (:require [ai.miniforge.response.interface :as response]))
              (defn boom []
                (response/throw-anomaly! :anomalies/not-found
                                         \"missing\"
                                         {:id 7}))"
          {:keys [violations]} (exc/analyze-content "foo.clj" src)]
      (is (= 1 (count violations)))
      (is (= :throw (:kind (first violations)))))))

(deftest detects-exception-class-instantiation
  (testing "(IllegalArgumentException. ...) is flagged"
    (let [src "(ns ai.miniforge.foo.core)
              (defn boom []
                (throw (IllegalArgumentException. \"bad input\")))"
          {:keys [violations]} (exc/analyze-content "foo.clj" src)]
      ;; Both the throw and the ctor count — at minimum one of them is observed.
      (is (pos? (count violations)))
      (is (some #{:throw :ctor} (map :kind violations))))))

(deftest reports-line-and-column
  (testing "every violation carries a usable line:col location"
    (let [src "(ns ai.miniforge.foo.core)\n\n(defn b []\n  (throw (ex-info \"m\" {})))"
          {:keys [violations]} (exc/analyze-content "foo.clj" src)]
      (is (every? #(pos-int? (:line %)) violations))
      (is (every? #(some? (:column %)) violations)))))

(deftest ignores-throws-in-docstrings
  (testing "the word `throw` inside a string literal does not trigger"
    (let [src "(ns ai.miniforge.foo.core)
              (defn ok
                \"This function never throws nor calls ex-info.\"
                []
                :result)"
          {:keys [violations]} (exc/analyze-content "foo.clj" src)]
      (is (= 0 (count violations))))))

(deftest snippet-is-single-line-and-truncated
  (testing "snippet output collapses whitespace and limits length"
    (let [src "(ns ai.miniforge.foo.core)
              (defn b [] (throw (ex-info \"x\" {:a 1 :b 2})))"
          {:keys [violations]} (exc/analyze-content "foo.clj" src)
          snippet (:snippet (first violations))]
      (is (string? snippet))
      (is (not (str/includes? snippet "\n")))
      (is (<= (count snippet) 124)))))
