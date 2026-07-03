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

(ns ai.miniforge.compliance-scanner.exceptions-as-data.local-boundary-classification-test
  "Local boundary throwers retained for compatibility are informational, while
   ordinary throwers in component code remain actionable."
  (:require
   [ai.miniforge.compliance-scanner.exceptions-as-data :as exc]
   [clojure.test :refer [deftest is testing]]))

(deftest documented-boundary-wrapper-is-local-boundary
  (testing "defn docstrings can mark retained component-local boundary throwers"
    (let [src "(ns ai.miniforge.foo.core)
              (defn make-widget [request]
                (if (:ok? request)
                  request
                  {:anomaly/type :invalid-input}))

              (defn make-widget!
                \"Boundary wrapper around `make-widget`. Calls the canonical
                 anomaly-returning fn and throws only for legacy callers.

                 Prefer `make-widget` in non-boundary code.\"
                [request]
                (let [result (make-widget request)]
                  (if (:anomaly/type result)
                    (throw (ex-info \"Invalid widget\" result))
                    result)))"
          {:keys [violations]} (exc/analyze-content "foo.clj" src)]
      (is (= 1 (count violations)))
      (is (= :local-boundary (:classification (first violations)))))))

(deftest throwing-boundary-with-data-equivalent-is-local-boundary
  (testing "response/throw-anomaly! bridges with anomaly-returning equivalents are local boundaries"
    (let [src "(ns ai.miniforge.foo.core
                (:require [ai.miniforge.response.interface :as response]))
              (defn load-thing
                \"Load a thing.

                 Throws via `response/throw-anomaly!` when no thing exists.

                 For an anomaly-returning equivalent that callers can branch
                 on as data, use `try-load-thing` directly.\"
                [id]
                (let [result (try-load-thing id)]
                  (if (:anomaly/type result)
                    (response/throw-anomaly! :anomalies/not-found
                                             (:anomaly/message result)
                                             (:anomaly/data result))
                    result)))"
          {:keys [violations]} (exc/analyze-content "foo.clj" src)]
      (is (= 1 (count violations)))
      (is (= :local-boundary (:classification (first violations)))))))

(deftest deprecated-exceptions-as-data-wrapper-is-local-boundary
  (testing "deprecated throwers with exceptions-as-data metadata are local boundaries"
    (let [src "(ns ai.miniforge.foo.core)
              (defn unwrap-anomaly [result]
                (if (:ok? result)
                  (:data result)
                  {:anomaly/type :fault}))

              (defn unwrap
                \"Extract data from an ok result; throw on error.\"
                {:deprecated \"exceptions-as-data — prefer unwrap-anomaly\"}
                [result]
                (if (:ok? result)
                  (:data result)
                  (throw (ex-info \"Unwrap called on error result\" result))))"
          {:keys [violations]} (exc/analyze-content "foo.clj" src)]
      (is (= 1 (count violations)))
      (is (= :local-boundary (:classification (first violations)))))))

(deftest undocumented-bang-thrower-remains-cleanup-needed
  (testing "a bang name alone is not enough to exempt a throw"
    (let [src "(ns ai.miniforge.foo.core)
              (defn fetch!
                \"Fetch remote data.\"
                [url]
                (throw (ex-info \"GET request failed\" {:url url})))"
          {:keys [violations]} (exc/analyze-content "foo.clj" src)]
      (is (= 1 (count violations)))
      (is (= :cleanup-needed (:classification (first violations)))))))
