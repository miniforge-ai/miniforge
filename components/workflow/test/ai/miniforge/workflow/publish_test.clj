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

(ns ai.miniforge.workflow.publish-test
  (:require
   [clojure.test :refer [deftest is]]
   [ai.miniforge.workflow.publish :as publish]
   [babashka.fs :as fs]))

(deftest directory-publisher-test
  (let [temp-dir (fs/create-temp-dir {:prefix "workflow-publish-"})
        publisher (publish/create-directory-publisher temp-dir)
        result (publish/publish! publisher
                                 {:publication/path "reports/out.edn"
                                  :publication/content {:status :ok :count 3}})]
    (try
      (is (:success? result))
      (is (fs/exists? (:publication/path result)))
      (is (= {:status :ok :count 3}
             (read-string (slurp (:publication/path result)))))
      (finally
        (fs/delete-tree temp-dir)))))
