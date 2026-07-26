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
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

;; A real map resource shipped by this component, used for the happy path.
(def ^{:stratum 0} ^:private a-real-resource "config/default-user-config-fallback.edn")

(def ^{:stratum 0} ^:private a-missing-resource "config/does-not-exist-xyz.edn")

;; Test fixtures (under test/resource_test_fixtures/, on the test classpath).
;; The malformed fixture uses a .txt extension so the EDN linter does not
;; reject its deliberately invalid content; the loader reads by path, not
;; extension.
(def ^{:stratum 0} ^:private a-malformed-resource "resource_test_fixtures/malformed-edn.txt")

(def ^{:stratum 0} ^:private a-non-map-resource "resource_test_fixtures/non-map.edn")

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} load-config-resource-happy-path
  (testing "returns the parsed map for a resource on the classpath"
    (is (map? (resource/load-config-resource a-real-resource))))
  (testing "an empty required-keys set is a no-op"
    (is (map? (resource/load-config-resource a-real-resource [])))))

(deftest ^{:stratum 1} load-config-resource-missing-resource
  (testing "throws a clear ex-info naming the missing resource"
    (let [ex (try (resource/load-config-resource a-missing-resource)
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (instance? clojure.lang.ExceptionInfo ex))
      (is (= a-missing-resource (:config/resource (ex-data ex))))))
  (testing "preserves caller-supplied diagnostic ex-data"
    (let [ex (try (resource/load-config-resource
                   a-missing-resource
                   nil
                   {:hint "add the component resources to the classpath"})
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (instance? clojure.lang.ExceptionInfo ex))
      (is (= a-missing-resource (:config/resource (ex-data ex))))
      (is (= "add the component resources to the classpath"
             (:hint (ex-data ex)))))))

(deftest ^{:stratum 1} load-config-resource-extra-ex-data-contract
  (testing "throws a clear ex-info when caller-supplied diagnostic ex-data is not a map"
    (let [ex (try (resource/load-config-resource a-real-resource nil [:hint "bad"])
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (instance? clojure.lang.ExceptionInfo ex))
      (is (= a-real-resource (:config/resource (ex-data ex))))
      (is (= [:hint "bad"] (:config/extra-ex-data (ex-data ex)))))))

(deftest ^{:stratum 1} load-config-resource-missing-key
  (testing "throws when a required key is absent, naming the key"
    (let [ex (try (resource/load-config-resource a-real-resource [:definitely-not-a-key])
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (instance? clojure.lang.ExceptionInfo ex))
      (is (= [:definitely-not-a-key] (:config/missing-keys (ex-data ex)))))))

(deftest ^{:stratum 1} load-config-resource-malformed
  (testing "throws a clear ex-info for malformed EDN"
    (let [ex (try (resource/load-config-resource a-malformed-resource)
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (instance? clojure.lang.ExceptionInfo ex))
      (is (= a-malformed-resource (:config/resource (ex-data ex))))
      (is (= :invalid-config (:config/error (ex-data ex))))
      (is (= :malformed-edn (:config/invalid-config-reason (ex-data ex)))))))

(deftest ^{:stratum 1} load-config-resource-unreadable-resource
  (testing "distinguishes resource read failures from malformed EDN"
    (let [missing-file-url (java.net.URL.
                            (str "file:/tmp/miniforge-missing-"
                                 (random-uuid)
                                 ".edn"))
          ex (with-redefs [io/resource (constantly missing-file-url)]
               (try (resource/load-config-resource a-real-resource)
                    (catch clojure.lang.ExceptionInfo e e)))]
      (is (instance? clojure.lang.ExceptionInfo ex))
      (is (= a-real-resource (:config/resource (ex-data ex))))
      (is (= :invalid-config (:config/error (ex-data ex))))
      (is (= :unreadable-resource
             (:config/invalid-config-reason (ex-data ex)))))))

(deftest ^{:stratum 1} load-config-resource-non-map
  (testing "throws when the EDN parses to a non-map value"
    (let [ex (try (resource/load-config-resource a-non-map-resource)
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (instance? clojure.lang.ExceptionInfo ex))
      (is (= a-non-map-resource (:config/resource (ex-data ex))))
      (is (= :invalid-config (:config/error (ex-data ex))))
      (is (= :not-a-map (:config/invalid-config-reason (ex-data ex)))))))

(deftest ^{:stratum 1} read-config-resource-or-fail-open
  (testing "returns the fallback for a missing resource"
    (is (= {:fallback true}
           (resource/read-config-resource-or a-missing-resource {:fallback true}))))
  (testing "returns the fallback for malformed EDN"
    (is (= {:fallback true}
           (resource/read-config-resource-or a-malformed-resource {:fallback true}))))
  (testing "returns the fallback for a non-map value"
    (is (= {:fallback true}
           (resource/read-config-resource-or a-non-map-resource {:fallback true}))))
  (testing "returns the parsed map for a present resource"
    (is (map? (resource/read-config-resource-or a-real-resource {:fallback true})))))
