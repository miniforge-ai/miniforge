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

(ns ai.miniforge.anomaly.interface.type-vocabulary-test
  "The constructor's type-vocabulary check is the only place this
   component throws. An unknown type is a programmer error, so
   failing fast at construction is the right thing."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.anomaly.interface :as anomaly]))

(deftest standard-vocabulary-is-locked
  (testing "the published vocabulary mirrors the cognitect set plus :fatal"
    (is (= #{:not-found :invalid-input :unauthorized :fault :unavailable
             :conflict :timeout :unsupported :fatal}
           anomaly/anomaly-types))))

(deftest every-standard-type-constructs-cleanly
  (testing "the constructor accepts every type in the vocabulary"
    (doseq [t anomaly/anomaly-types]
      (let [a (anomaly/anomaly t "msg" {})]
        (is (= t (:anomaly/type a)))))))

(deftest unknown-type-throws-illegal-argument
  (testing "unknown types raise IllegalArgumentException"
    (is (thrown? IllegalArgumentException
                 (anomaly/anomaly :no-such-type "msg" {})))
    (is (thrown? IllegalArgumentException
                 (anomaly/anomaly :NotFound "msg" {})))
    (is (thrown? IllegalArgumentException
                 (anomaly/anomaly nil "msg" {})))
    (is (thrown? IllegalArgumentException
                 (anomaly/anomaly "not-a-keyword" "msg" {})))))

(deftest unknown-type-error-mentions-vocabulary
  (testing "the thrown message lists the offending type"
    (try
      (anomaly/anomaly :totally-bogus "m" {})
      (is false "expected IllegalArgumentException")
      (catch IllegalArgumentException e
        (is (re-find #"totally-bogus" (.getMessage e)))))))
