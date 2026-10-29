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
(ns ai.miniforge.spec-parser.workflow-type-test
  "A spec's explicit :spec/workflow-type and :spec/workflow-version survive
   normalization; :workflow/type is the fallback, :canonical-sdlc the default."
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.spec-parser.interface :as spec-parser]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} base-spec
  {:spec/title "T" :spec/description "D"})

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} explicit-spec-workflow-type-test
  (testing ":spec/workflow-type is kept"
    (is (= :quick-fix
           (:spec/workflow-type
            (spec-parser/normalize-spec (assoc base-spec :spec/workflow-type :quick-fix))))))
  (testing ":spec/workflow-type wins over :workflow/type"
    (is (= :quick-fix
           (:spec/workflow-type
            (spec-parser/normalize-spec (assoc base-spec
                                               :spec/workflow-type :quick-fix
                                               :workflow/type :canonical-sdlc))))))
  (testing ":workflow/type is used when :spec/workflow-type is absent"
    (is (= :quick-fix
           (:spec/workflow-type
            (spec-parser/normalize-spec (assoc base-spec :workflow/type "quick-fix"))))))
  (testing "a spec naming no workflow gets :canonical-sdlc"
    (is (= :canonical-sdlc
           (:spec/workflow-type (spec-parser/normalize-spec base-spec))))))

(deftest ^{:stratum 1} explicit-spec-workflow-version-test
  (testing ":spec/workflow-version is kept and wins over :workflow/version"
    (is (= "2.0.0"
           (:spec/workflow-version
            (spec-parser/normalize-spec (assoc base-spec
                                               :spec/workflow-version "2.0.0"
                                               :workflow/version "1.0.0")))))))

(deftest ^{:stratum 1} edn-spec-file-workflow-type-test
  (testing "an EDN spec file's :spec/workflow-type reaches the parsed spec"
    (let [tmp (fs/create-temp-file {:suffix ".edn"})]
      (try
        (spit (fs/file tmp) (pr-str (assoc base-spec :spec/workflow-type :quick-fix)))
        (let [spec (spec-parser/parse-spec-file (str tmp))]
          (is (= :quick-fix (:spec/workflow-type spec)))
          (is (true? (:valid? (spec-parser/validate-spec spec)))))
        (finally
          (fs/delete-if-exists tmp))))))
