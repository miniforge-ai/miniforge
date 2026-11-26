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

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private corrupt-json "{\"mcpServers\": {\"other\": ")

(defn- ^{:stratum 0} file-names
  [dir]
  (set (map fs/file-name (fs/list-dir dir))))

(deftest ^{:stratum 0} write-file-atomically-dangling-symlink-test
  (fs/with-temp-dir [dir {}]
    (let [real (str dir "/dotfiles/real.json")
          link (str dir "/link.json")]
      (fs/create-dirs (str dir "/dotfiles"))
      (fs/create-sym-link link "dotfiles/real.json")
      (tasks/write-file-atomically! link "new")
      (testing "a link whose backing file is missing is kept, and the backing file created"
        (is (fs/sym-link? link))
        (is (= "new" (slurp real)))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} read-json-file-test
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
        (is (= corrupt-json (slurp path))))
      (testing "content after the first JSON value is rejected, not ignored"
        (spit path "{} {\"mcpServers\":{\"other\":{}}}")
        (is (thrown? clojure.lang.ExceptionInfo (tasks/read-json-file path))))
      (testing "a non-object root is rejected"
        (spit path "[1]")
        (is (thrown? clojure.lang.ExceptionInfo (tasks/read-json-file path))))
      (testing "a blank file holds nothing to lose and reads as empty"
        (spit path "  \n")
        (is (= {} (tasks/read-json-file path)))))))

(deftest ^{:stratum 1} write-json-file-test
  (fs/with-temp-dir [dir {}]
    (let [path (str dir "/nested/config.json")]
      (with-out-str (tasks/write-json-file path {"a" 1}))
      (testing "creates parents and writes parseable JSON"
        (is (= {"a" 1} (tasks/read-json-file path))))
      (testing "leaves no temp file behind"
        (is (= #{"config.json"} (file-names (fs/parent path))))))))

(deftest ^{:stratum 1} write-file-atomically-preserves-target-test
  (fs/with-temp-dir [dir {}]
    (let [real (str dir "/real.json")
          link (str dir "/link.json")]
      (spit real "old")
      (fs/create-sym-link link real)
      (when-not (fs/windows?)
        (fs/set-posix-file-permissions real "rw-------"))
      (tasks/write-file-atomically! link "new")
      (testing "a symlinked config is written through, the link survives"
        (is (fs/sym-link? link))
        (is (= "new" (slurp real))))
      (testing "the existing mode is kept"
        (when-not (fs/windows?)
          (is (= (fs/str->posix "rw-------")
                 (fs/posix-file-permissions real)))))
      (testing "no temp file is left in the directory"
        (is (= #{"real.json" "link.json"} (file-names dir)))))))

(deftest ^{:stratum 1} write-file-atomically-symlink-cycle-test
  (fs/with-temp-dir [dir {}]
    (let [link (str dir "/link.json")
          back (str dir "/back.json")]
      (fs/create-sym-link link "back.json")
      (fs/create-sym-link back "link.json")
      (testing "a link cycle throws instead of looping, and both links are kept"
        (is (thrown? clojure.lang.ExceptionInfo (tasks/write-file-atomically! link "new")))
        (is (= #{"link.json" "back.json"} (file-names dir)))))))

(deftest ^{:stratum 1} symlink-loop-is-not-treated-as-missing-test
  (fs/with-temp-dir [dir {}]
    (let [link (fn [i] (str dir "/link" i ".json"))
          hops 45]
      (spit (link hops) "{\"mcpServers\": {\"other\": {}}}")
      (doseq [i (range hops)]
        (fs/create-sym-link (link i) (str "link" (inc i) ".json")))
      (testing "a link chain past the hop bound throws instead of reading as empty"
        (is (thrown? clojure.lang.ExceptionInfo (tasks/read-json-file (link 0))))
        (is (thrown? clojure.lang.ExceptionInfo (tasks/write-file-atomically! (link 0) "x"))))
      (testing "the backing config is untouched"
        (is (= "{\"mcpServers\": {\"other\": {}}}" (slurp (link hops))))))))
