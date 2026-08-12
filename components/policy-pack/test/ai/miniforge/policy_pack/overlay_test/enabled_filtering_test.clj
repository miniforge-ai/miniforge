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
(ns ai.miniforge.policy-pack.overlay-test.enabled-filtering-test
  "Tests for `:rule/enabled?` filtering in
   `applicability/filter-applicable-rules`. Split out of `overlay_test.clj`
   (rule 210) — this test only exercises the applicability namespace and
   needs no pack-resolution fixtures beyond `make-rule`."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.policy-pack.applicability :as applicability]
   [ai.miniforge.policy-pack.overlay-test.fixtures :as fixtures]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} filter-applicable-rules-respects-enabled-test
  (testing "disabled rules are excluded by filter-applicable-rules"
    (let [enabled-rule  (fixtures/make-rule :test/enabled :low)
          disabled-rule (assoc (fixtures/make-rule :test/disabled :low) :rule/enabled? false)
          rules         [enabled-rule disabled-rule]
          context       {:artifact {:artifact/path "foo.clj"}
                         :phase    :implement}
          result        (applicability/filter-applicable-rules rules context)]
      (is (= 1 (count result)))
      (is (= :test/enabled (:rule/id (first result))))))

  (testing "rules without :rule/enabled? default to enabled"
    (let [rules   [(fixtures/make-rule :test/implicit-enabled :low)]
          context {:artifact {:artifact/path "foo.clj"} :phase :implement}
          result  (applicability/filter-applicable-rules rules context)]
      (is (= 1 (count result))))))
