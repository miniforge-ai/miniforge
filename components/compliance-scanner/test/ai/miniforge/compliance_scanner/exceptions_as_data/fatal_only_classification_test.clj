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

(deftest registry-contract-message-key-is-fatal-only
  (testing "localized registry contract keys are programmer-error guards"
    (let [src "(ns ai.miniforge.foo.registry)
              (defn register-widget! [widget-key f]
                (when-not (keyword? widget-key)
                  (throw (IllegalArgumentException.
                           (messages/t :widgets/non-keyword-key
                                       {:value (pr-str widget-key)}))))
                (when-not (ifn? f)
                  (throw (IllegalArgumentException.
                           (messages/t :widgets/non-fn-value
                                       {:value (pr-str f)})))))"
          {:keys [violations]} (exc/analyze-content "registry.clj" src)]
      (is (= 2 (count violations)))
      (is (every? #(= :fatal-only (:classification %)) violations)))))

(deftest registry-resolution-invariant-is-fatal-only
  (testing "validated-but-missing registry resolution keys are fatal-only"
    (let [src "(ns ai.miniforge.foo.registry)
              (defn resolve-widget [k]
                (or (lookup-widget k)
                    (throw (IllegalStateException.
                             (messages/t :widgets/unregistered-at-resolve
                                         {:widget k})))))"
          {:keys [violations]} (exc/analyze-content "registry.clj" src)]
      (is (= 1 (count violations)))
      (is (= :fatal-only (:classification (first violations)))))))

(deftest no-matching-reflection-helper-is-fatal-only
  (testing "reflection helper arity misses are programmer errors"
    (let [src "(ns ai.miniforge.foo.reflect)
              (defn construct [ctor]
                (or ctor
                    (throw (ex-info \"No matching constructor\" {:arity 3}))))"
          {:keys [violations]} (exc/analyze-content "reflect.clj" src)]
      (is (= 1 (count violations)))
      (is (= :fatal-only (:classification (first violations)))))))

(deftest unmapped-dispatch-table-category-is-fatal-only
  (testing "unmapped category guards protect closed dispatch tables"
    (let [src "(ns ai.miniforge.foo.dispatch)
              (defn category-type [category]
                (or (lookup category)
                    (throw (IllegalArgumentException.
                            (str \"Unmapped category: \" category)))))"
          {:keys [violations]} (exc/analyze-content "dispatch.clj" src)]
      (is (= 1 (count violations)))
      (is (= :fatal-only (:classification (first violations)))))))

(deftest nested-invalid-config-marker-is-fatal-only
  (testing "nested invalid-config ex-data marks fail-fast config guards"
    (let [src "(ns ai.miniforge.foo.config)
              (defn read-config [path]
                (throw (ex-info \"Malformed config resource\"
                                (assoc {:config/resource path}
                                       :config/error :invalid-config))))"
          {:keys [violations]} (exc/analyze-content "config.clj" src)]
      (is (= 1 (count violations)))
      (is (= :fatal-only (:classification (first violations)))))))

(deftest interrupted-exception-rethrow-is-fatal-only
  (testing "InterruptedException rethrows preserve cancellation"
    (let [src "(ns ai.miniforge.foo.loop)
              (defn poll []
                (try
                  (Thread/sleep 1)
                  (catch InterruptedException e
                    (.interrupt (Thread/currentThread))
                    (throw e))))"
          {:keys [violations]} (exc/analyze-content "loop.clj" src)]
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
