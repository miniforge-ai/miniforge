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
(ns ai.miniforge.messages.missing-key-test
  "A missing catalog key still renders as its name, and says so once."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ai.miniforge.messages.core :as core]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} missing-key-renders-name-and-warns-once
  (let [catalog (delay {:a/present "here"})
        err (java.io.StringWriter.)]
    (binding [*err* err]
      (testing "present keys render silently"
        (is (= "here" (core/t catalog :a/present)))
        (is (str/blank? (str err))))
      (testing "a missing key renders its name and warns on stderr"
        (is (= "gone" (core/t catalog :a/gone)))
        (is (str/includes? (str err) ":a/gone")))
      (testing "the same key warns only once"
        (let [before (count (str err))]
          (core/t catalog :a/gone)
          (is (= before (count (str err)))))))))
