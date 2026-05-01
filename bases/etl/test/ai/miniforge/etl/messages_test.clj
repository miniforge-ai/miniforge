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

(ns ai.miniforge.etl.messages-test
  "Smoke tests for the etl base's message catalog. Guards against the
   resource path or section key drifting and against keys silently
   disappearing from the EDN catalog."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.miniforge.etl.messages :as sut]))

(deftest catalog-resolves-keyless-message-test
  (testing "static catalog keys resolve to non-blank strings"
    (is (string? (sut/t :run/complete)))
    (is (string? (sut/t :run/failed)))
    (is (string? (sut/t :validate/ok)))
    (is (string? (sut/t :help/usage)))))

(deftest catalog-interpolates-params-test
  (testing "{placeholder} markers are substituted from the param map"
    (is (= "  run-id: abc-123"
           (sut/t :run/run-id {:value "abc-123"})))
    (is (= "2 pipeline(s) discovered under .:"
           (sut/t :list/header {:count 2 :path "."})))
    (is (= "  wrote: /tmp/out.edn"
           (sut/t :run/wrote {:path "/tmp/out.edn"})))))
