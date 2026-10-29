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
(ns ai.miniforge.agent.artifact-session-command-test
  "Resolution of the MCP context server and PreToolUse hook commands from
   the directory miniforge runs in and the binaries on PATH."
  (:require
   [ai.miniforge.agent.artifact-session :as session]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest testing is]])
  (:import
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]))

;------------------------------------------------------------------------------ Layer 0

;; Fixtures
(def ^{:stratum 0} ^:private env-override?
  "The env overrides win over every case below; skip when either is set."
  (some? (or (System/getenv "MINIFORGE_CMD")
             (System/getenv "MINIFORGE_MCP_CMD"))))

(def ^{:stratum 0} ^:private miniforge-bb-edn
  "A bb.edn defining the tasks the helper commands run, as miniforge's does."
  "{:tasks {miniforge {:task (println :cli)}
          mcp-context-server {:task (println :server)}}}")

(def ^{:stratum 0} ^:private user-bb-edn
  "A user repository's own bb.edn."
  "{:tasks {test {:task (shell \"make test\")}}}")

(defn- ^{:stratum 0} temp-dir!
  "Create a temp directory and return its canonical File."
  []
  (.getCanonicalFile
   (.toFile (Files/createTempDirectory "mf-command-test-"
                                       (into-array FileAttribute [])))))

(defn- ^{:stratum 0} write-file!
  "Write `content` to `root`/`rel-path`, creating parent directories."
  [root rel-path content]
  (let [f (io/file root rel-path)]
    (io/make-parents f)
    (spit f content)
    f))

(defn- ^{:stratum 0} delete-tree!
  "Delete `root` and everything under it."
  [root]
  (doseq [f (reverse (file-seq root))]
    (io/delete-file f true)))

(defn- ^{:stratum 0} resolve-from
  "Resolve the server and hook commands with user.dir set to `dir` and only
   the commands in the set `on-path` found on PATH."
  [dir on-path]
  (let [original (System/getProperty "user.dir")]
    (try
      (System/setProperty "user.dir" (str dir))
      (with-redefs [session/command-on-path? on-path]
        {:server (session/server-command "/tmp/artifacts" nil)
         :hook   (session/resolve-miniforge-command)})
      (finally
        (System/setProperty "user.dir" original)))))

;------------------------------------------------------------------------------ Layer 1

;; A user repository's bb.edn is not miniforge's
(deftest ^{:stratum 1} user-repo-bb-edn-is-not-used-test
  (when-not env-override?
    (testing "a repo with its own bb.edn resolves to the installed mf binary"
      (let [root (temp-dir!)]
        (try
          (write-file! root "bb.edn" user-bb-edn)
          (let [{:keys [server hook]} (resolve-from (io/file root "src") #{"mf"})]
            (is (= {:command "mf"
                    :args ["context-server" "--artifact-dir" "/tmp/artifacts"]}
                   server))
            (is (= ["mf"] hook)))
          (finally (delete-tree! root)))))))

(deftest ^{:stratum 1} unparseable-bb-edn-is-not-used-test
  (when-not env-override?
    (testing "a bb.edn that does not parse is skipped"
      (let [root (temp-dir!)]
        (try
          (write-file! root "bb.edn" "{:tasks {unbalanced")
          (is (= ["mf"] (:hook (resolve-from root #{"mf"}))))
          (finally (delete-tree! root)))))))

(deftest ^{:stratum 1} miniforge-bb-edn-above-user-bb-edn-wins-test
  (when-not env-override?
    (testing "the walk skips a user bb.edn and finds miniforge's further up"
      (let [root (temp-dir!)
            root-path (.getPath root)]
        (try
          (write-file! root "bb.edn" miniforge-bb-edn)
          (write-file! root "nested/bb.edn" user-bb-edn)
          (let [{:keys [server hook]} (resolve-from (io/file root "nested") #{"mf"})]
            (is (= "bb" (:command server)))
            (is (= ["--config" (str root-path "/bb.edn") "--deps-root" root-path
                    "mcp-context-server"]
                   (take 5 (:args server))))
            (is (= ["bb" "--config" (str root-path "/bb.edn")
                    "--deps-root" root-path "miniforge"]
                   hook)))
          (finally (delete-tree! root)))))))

;; Installed binaries, no bb.edn
(deftest ^{:stratum 1} hook-uses-installed-mf-binary-test
  (when-not env-override?
    (testing "Homebrew, scoop and install:local install the binary as mf"
      (let [root (temp-dir!)]
        (try
          (let [{:keys [server hook]} (resolve-from root #{"mf"})]
            (is (= "mf" (:command server)))
            (is (= ["mf"] hook)))
          (finally (delete-tree! root)))))))

(deftest ^{:stratum 1} miniforge-binary-name-still-resolves-test
  (when-not env-override?
    (testing "a binary installed as miniforge is used when mf is absent"
      (let [root (temp-dir!)]
        (try
          (let [{:keys [server hook]} (resolve-from root #{"miniforge"})]
            (is (= "miniforge" (:command server)))
            (is (= ["miniforge"] hook)))
          (finally (delete-tree! root)))))))
