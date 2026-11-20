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

(ns ai.miniforge.lsp-mcp-bridge.tasks-test
  "Tests for config file reading and writing in the setup tasks."
  (:require
   [ai.miniforge.lsp-mcp-bridge.tasks :as tasks]
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]))

(def ^:private corrupt-json "{\"mcpServers\": {\"other\": ")

(deftest read-json-file-test
  (fs/with-temp-dir [dir {}]
    (let [path (str dir "/config.json")]
      (testing "a missing file reads as an empty map"
        (is (= {} (tasks/read-json-file path))))
      (testing "valid JSON is parsed"
        (spit path "{\"a\": 1}")
        (is (= {"a" 1} (tasks/read-json-file path))))
      (testing "unparseable JSON throws and the file is left untouched"
        (spit path corrupt-json)
        (is (thrown? clojure.lang.ExceptionInfo (tasks/read-json-file path)))
        (is (= corrupt-json (slurp path)))))))

(deftest write-json-file-test
  (fs/with-temp-dir [dir {}]
    (let [path (str dir "/nested/config.json")]
      (with-out-str (tasks/write-json-file path {"a" 1}))
      (testing "creates parents and writes parseable JSON"
        (is (= {"a" 1} (tasks/read-json-file path))))
      (testing "leaves no temp file behind"
        (is (not (fs/exists? (str path ".tmp"))))))))
