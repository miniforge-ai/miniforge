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

(ns ai.miniforge.dag-primitives.unwrap-throwing-compat-test
  "Backward-compat coverage for the deprecated throwing `unwrap`.

   Existing callers expect ok→data, err→throw. The deprecation only
   redirects new code to `unwrap-anomaly`; the legacy contract is
   preserved here."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.dag-primitives.interface :as dp]))

(deftest unwrap-still-returns-data-on-ok
  (testing "deprecated thrower returns ok data unchanged"
    (is (= 42 (dp/unwrap (dp/ok 42))))))

(deftest unwrap-still-throws-on-err
  (testing "deprecated thrower throws ExceptionInfo on err input"
    (is (thrown? clojure.lang.ExceptionInfo
                 (dp/unwrap (dp/err :cycle "boom"))))))
