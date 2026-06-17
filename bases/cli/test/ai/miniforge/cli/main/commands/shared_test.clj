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

(ns ai.miniforge.cli.main.commands.shared-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.cli.main.commands.shared :as sut]))

(deftest optional-provider-registry-test
  (testing "registered optional providers are available by symbol"
    (let [provider (fn [x] [:ok x])]
      (with-redefs [sut/optional-functions {}]
        (sut/register-optional-fn! 'example/provider provider)
        (is (identical? provider (sut/try-resolve 'example/provider)))
        (is (= [:ok 42] (sut/try-resolve-fn 'example/provider 42))))))

  (testing "missing or failing providers return nil to preserve fallback flow"
    (with-redefs [sut/optional-functions {'example/failing (fn [] (throw (ex-info "boom" {})))}]
      (is (nil? (sut/try-resolve 'example/missing)))
      (is (nil? (sut/try-resolve-fn 'example/missing)))
      (is (nil? (sut/try-resolve-fn 'example/failing))))))
