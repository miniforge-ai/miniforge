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
(ns ai.miniforge.cli.workflow-runner.paths-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.cli.workflow-runner.paths :as sut])
  (:import
   (java.io File)))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private foreign-separator
  "The PATH separator of the *other* platform family, which must never act as a
   delimiter here: `;` on POSIX, `:` on Windows."
  (if (= ";" File/pathSeparator) ":" ";"))

(defn- ^{:stratum 0} join-path-entries
  [entries]
  (str/join File/pathSeparator entries))

(defn- ^{:stratum 0} relative-entry
  [dir]
  (str (fs/relativize (fs/cwd) dir)))

(defn- ^{:stratum 0} mark-executable!
  "POSIX permission bits do not exist on Windows, where `File/setExecutable`
   is the only way to set the bit this test's subject reads."
  [path]
  (if (fs/windows?)
    (.setExecutable ^java.io.File (fs/file path) true)
    (fs/set-posix-file-permissions path "rwxr-xr-x")))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} test-path-splitting-honours-the-platform-separator
  (testing "given a PATH joined with the platform separator → one entry per element"
    (is (= ["/usr/local/bin" "/usr/bin" "/bin"]
           (#'sut/split-path-entries
            (join-path-entries ["/usr/local/bin" "/usr/bin" "/bin"])))))
  (testing "given an entry containing the other platform's separator → kept whole"
    (let [entry (str "/opt/tools" foreign-separator "extra")]
      (is (= [entry] (#'sut/split-path-entries entry)))))
  (testing "given an unset PATH → the empty entry, never a nil deref"
    (is (= [""] (#'sut/split-path-entries nil)))))

(defn- ^{:stratum 1} temp-executable
  "Creates an empty executable file with a name no real PATH lookup can
   resolve, so `fs/which` misses and the PATH scan fallback is the code path
   under test. The file is never run — only its executable bit is read."
  []
  (let [dir (fs/create-temp-dir {:prefix "mf-paths-"})
        cmd (str "mf-fake-cli-" (random-uuid))
        exe (fs/path dir cmd)]
    (spit (fs/file exe) "")
    (mark-executable! exe)
    {:dir (str dir) :cmd cmd}))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} test-path-scan-fallback-returns-an-absolute-path
  (testing "given a relative PATH entry → the resolved command path is absolute"
    (let [{:keys [dir cmd]} (temp-executable)]
      (try
        (let [resolved (with-redefs-fn {#'sut/path-entries (fn [] [(relative-entry dir)])}
                         (fn [] (sut/resolve-cli-command-path cmd)))]
          (is (some? resolved))
          (is (fs/absolute? resolved))
          (is (= (str (fs/canonicalize (fs/path dir cmd)))
                 (str (fs/canonicalize resolved)))))
        (finally
          (fs/delete-tree dir))))))
