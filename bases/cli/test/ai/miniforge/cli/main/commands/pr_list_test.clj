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
(ns ai.miniforge.cli.main.commands.pr-list-test
  (:require
   [babashka.fs :as fs]
   [babashka.process :as process]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.cli.main.commands.pr :as sut]
   [ai.miniforge.cli.main.commands.shared :as shared]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} missing-gh
  "What babashka.process/sh throws when gh is not installed."
  [& _]
  (throw (java.io.IOException. "Cannot run program \"gh\": error=2, No such file or directory")))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} pr-list-without-gh-exits-1-test
  (testing "gh not on PATH: a plain error on stderr and exit 1, not a stack trace"
    (let [exit (atom :no-exit)
          err  (java.io.StringWriter.)]
      (with-redefs [fs/which     (fn [& _] nil)
                    process/sh   missing-gh
                    shared/exit! (fn [code] (reset! exit code))]
        (binding [*out* (java.io.StringWriter.)
                  *err* err]
          (sut/pr-list-cmd {:repo "owner/repo"} (constantly {}))))
      (is (= 1 @exit))
      (is (str/includes? (str err) "GitHub CLI (gh) not found on PATH")))))
