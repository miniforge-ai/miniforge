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

(ns ai.miniforge.config.resource-test
  (:require
   [ai.miniforge.config.resource :as resource]
   [clojure.test :refer [deftest is testing]]))

;; A real map resource shipped by this component, used for the happy path.
(def ^:private a-real-resource "config/default-user-config-fallback.edn")
(def ^:private a-missing-resource "config/does-not-exist-xyz.edn")

(deftest load-config-resource-happy-path
  (testing "returns the parsed map for a resource on the classpath"
    (is (map? (resource/load-config-resource a-real-resource))))
  (testing "an empty required-keys set is a no-op"
    (is (map? (resource/load-config-resource a-real-resource [])))))

(deftest load-config-resource-missing-resource
  (testing "throws a clear ex-info naming the missing resource"
    (let [ex (try (resource/load-config-resource a-missing-resource)
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (instance? clojure.lang.ExceptionInfo ex))
      (is (= a-missing-resource (:config/resource (ex-data ex)))))))

(deftest load-config-resource-missing-key
  (testing "throws when a required key is absent, naming the key"
    (let [ex (try (resource/load-config-resource a-real-resource [:definitely-not-a-key])
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (instance? clojure.lang.ExceptionInfo ex))
      (is (= [:definitely-not-a-key] (:config/missing-keys (ex-data ex)))))))

(deftest read-config-resource-or-fail-open
  (testing "returns the fallback for a missing resource"
    (is (= {:fallback true}
           (resource/read-config-resource-or a-missing-resource {:fallback true}))))
  (testing "returns the parsed map for a present resource"
    (is (map? (resource/read-config-resource-or a-real-resource {:fallback true})))))
