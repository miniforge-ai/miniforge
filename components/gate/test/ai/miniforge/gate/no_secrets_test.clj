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
(ns ai.miniforge.gate.no-secrets-test
  "The :no-secrets gate scans the files a change writes, names each
   finding by file and line, and never repeats the value."
  (:require [ai.miniforge.gate.interface :as gate]
            [ai.miniforge.gate.no-secrets :as no-secrets]
            [babashka.fs :as fs]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} gh-token
  "Shaped like a GitHub token; not a credential. Built from parts so the
   source holds no token-shaped literal."
  (str "ghp" "_" "abcdefghijklmnopqrstuvwxyz0123456789"))

(def ^{:stratum 0} opaque
  "A password literal with no recognisable shape."
  "hunter2hunter2")

(defn ^{:stratum 0} temp-git-repo
  "A git repo with `files` ({path content}) committed at HEAD, in a fresh
   temp directory. Returns the directory."
  [files]
  (let [dir (str (Files/createTempDirectory "no-secrets-test" (make-array FileAttribute 0)))]
    (shell/sh "git" "-C" dir "init" "-q")
    (shell/sh "git" "-C" dir "config" "user.email" "test@test")
    (shell/sh "git" "-C" dir "config" "user.name" "test")
    (shell/sh "git" "-C" dir "config" "commit.gpgsign" "false")
    (doseq [[path content] files]
      (let [f (java.io.File. dir path)]
        (.mkdirs (.getParentFile f))
        (spit f content)))
    (shell/sh "git" "-C" dir "add" "-A")
    (shell/sh "git" "-C" dir "commit" "-q" "-m" "base")
    dir))

(defn ^{:stratum 0} check
  "Run the :no-secrets gate on `artifact` with `ctx`."
  ([artifact] (check artifact {}))
  ([artifact ctx] (gate/check-gate :no-secrets artifact ctx)))

(defn ^{:stratum 0} sh!
  "Run git in DIR; the commit identity and signing come from temp-git-repo."
  [dir & args]
  (apply shell/sh "git" "-C" dir args))

(deftest ^{:stratum 0} added-lines-follows-hunk-headers-test
  (is (= [[3 "x"] [4 "+y"] [9 "z"]]
         (no-secrets/added-lines
          (str "--- a/f\n+++ b/f\n@@ -2,0 +3,2 @@\n+x\n++y\n@@ -5 +8,0 @@\n-gone\n@@ -7 +9 @@\n-old\n+z\n")))))

(deftest ^{:stratum 0} scratch-dir-is-owner-only-test
  (let [dir (no-secrets/scratch-dir)]
    (try
      (is (= "rwx------" (fs/posix->str (fs/posix-file-permissions dir))))
      (finally (fs/delete-tree dir)))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} code-files-are-scanned-test
  (testing "a token in a :code/files entry fails the gate (main read only :content)"
    (let [result (check {:code/files [{:path    "src/app/config.py"
                                       :content (str "import os\napi_key = '" gh-token "'\n")
                                       :action  :create}]})]
      (is (false? (:passed? result)))
      (is (= [{:file "src/app/config.py" :line 2}]
             (-> result :errors first :locations)))))
  (testing "every file is scanned, and an opaque password literal is caught"
    (let [result (check {:code/files [{:path "src/a.clj" :content "(ns a)"}
                                      {:path "deploy/run.sh" :content (str "#!/bin/sh\n\nGH_TOKEN=opaqueVALUE123 mf run\n")}
                                      {:path "app/db.py" :content (str "password = \"" opaque "\"\n")}]})]
      (is (false? (:passed? result)))
      (is (= #{{:file "deploy/run.sh" :line 3} {:file "app/db.py" :line 1}}
             (-> result :errors first :locations set))))))

(deftest ^{:stratum 1} findings-never-carry-the-value-test
  (testing "the result names file and line only"
    (let [result (check {:code/files [{:path "a.py" :content (str "token = '" gh-token "'\npassword = '" opaque "'")}]})
          text   (pr-str result)]
      (is (false? (:passed? result)))
      (is (str/includes? (-> result :errors first :message) "a.py:1"))
      (is (str/includes? (-> result :errors first :message) "a.py:2"))
      (is (not (str/includes? text gh-token)))
      (is (not (str/includes? text opaque))))))

(deftest ^{:stratum 1} artifact-content-is-still-scanned-test
  (testing ":content and :artifact/content are scanned as before"
    (is (= [{:file nil :line 2}]
           (-> (check {:content (str "x = 1\nGH_TOKEN=" gh-token)}) :errors first :locations)))
    (is (= [{:file "secrets.clj" :line 1}]
           (-> (check {:artifact/content (str "(def api-key \"" gh-token "\")")
                       :artifact/path "secrets.clj"})
               :errors first :locations)))))

(deftest ^{:stratum 1} ordinary-code-passes-test
  (testing "numbers, hashes, ids, token counts and code that reads a secret are not secrets"
    (is (:passed? (check {:code/files
                          [{:path    "src/a.clj"
                            :content (str/join "\n"
                                               ["(def retention-ms (* 20 24 60 60 1000))"
                                                "(def max-long 9223372036854775807)"
                                                "(def sha \"3f2a9c1d8e7b6a5f4c3d2e1f0a9b8c7d6e5f4a3b\")"
                                                "(def id #uuid \"123e4567-e89b-12d3-a456-426614174000\")"
                                                ";; MAX_TOKENS=4096, token_count=3, :tokens 12"
                                                "API_TOKEN = fetch_token()"
                                                "GH_TOKEN=$GH_TOKEN"
                                                "token = \"short\""])}]})))))

(deftest ^{:stratum 1} only-lines-new-against-head-count-test
  (let [fixture (str "(def fake-token \"" gh-token "\")")
        dir     (temp-git-repo {"test/fixture_test.clj" (str "(ns fixture-test)\n" fixture "\n")})
        ctx     {:execution/worktree-path dir}]
    (testing "a token the file already had at HEAD is not charged to this change"
      (is (:passed? (check {:code/files [{:path    "test/fixture_test.clj"
                                          :content (str "(ns fixture-test)\n" fixture "\n(def x 1)\n")}]}
                           ctx))))
    (testing "a token the change adds is, at its new line number"
      (is (= [{:file "test/fixture_test.clj" :line 4}]
             (-> (check {:code/files [{:path    "test/fixture_test.clj"
                                       :content (str "(ns fixture-test)\n" fixture "\n(def x 1)\n"
                                                     "(def y \"" opaque "\") (def api-key \"" gh-token "x\")\n")}]}
                        ctx)
                 :errors first :locations))))
    (testing "a new file has every line scanned"
      (is (= [{:file "src/new.clj" :line 1}]
             (-> (check {:code/files [{:path "src/new.clj" :content fixture}]} ctx)
                 :errors first :locations))))))

(deftest ^{:stratum 1} uncommitted-worktree-changes-are-scanned-test
  (testing "a retry that writes nothing is still judged on what the last attempt left"
    (let [dir (temp-git-repo {"src/app.clj" "(ns app)\n"})]
      (spit (str dir "/src/app.clj") (str "(ns app)\n(def api-key \"" gh-token "\")\n"))
      (spit (str dir "/deploy.env") (str "GH_TOKEN=opaqueVALUE123\n"))
      (is (= #{{:file "src/app.clj" :line 2} {:file "deploy.env" :line 1}}
             (-> (check {:code/files []} {:execution/worktree-path dir})
                 :errors first :locations set))))))

(defn ^{:stratum 1} locations
  "The set of {:file :line} findings for ARTIFACT checked in worktree DIR."
  [artifact dir]
  (-> (check artifact {:execution/worktree-path dir}) :errors first :locations set))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} an-added-copy-of-an-existing-line-counts-test
  (testing "a line already in the file elsewhere is exempt only where it was; the new copy is scanned"
    (let [line (str "GH_TOKEN=" gh-token)
          dir  (temp-git-repo {"README.md" (str "Example:\n" line "\n")})]
      (spit (str dir "/README.md") (str "Example:\n" line "\nRun with:\n" line "\n"))
      (is (= #{{:file "README.md" :line 4}} (locations {:code/files []} dir))))))

(deftest ^{:stratum 2} a-stale-artifact-cannot-hide-the-disk-test
  (testing "a clean artifact entry does not stop the changed on-disk file from being scanned"
    (let [dir (temp-git-repo {"src/app.py" "import os\n"})]
      (spit (str dir "/src/app.py") (str "import os\napi_key = '" gh-token "'\n"))
      (is (= #{{:file "src/app.py" :line 2}}
             (locations {:code/files [{:path "src/app.py" :content "import os\n"}]} dir))))))

(deftest ^{:stratum 2} committed-task-work-is-measured-from-the-task-base-test
  (let [dir  (temp-git-repo {"src/app.py" "import os\n"})
        base (str/trim (:out (sh! dir "rev-parse" "HEAD")))]
    (spit (str dir "/src/app.py") (str "import os\npassword = \"" opaque "\"\n"))
    (sh! dir "commit" "-qam" "task checkpoint")
    (testing "a credential committed after the task base is still found"
      (is (= #{{:file "src/app.py" :line 2}}
             (locations {:code/files [] :code/base-refs [base]} dir))))
    (testing "a resumed workspace's A...HEAD range resolves to the merge base"
      (is (= #{{:file "src/app.py" :line 2}}
             (locations {:code/files [] :code/base-refs [(str base "...HEAD")]} dir))))
    (testing "with no task base the gate falls back to HEAD, which already holds it"
      (is (= #{} (locations {:code/files []} dir))))))

(deftest ^{:stratum 2} names-git-would-quote-are-scanned-test
  (testing "NUL-delimited listing: a non-ASCII or space-padded name is read as it is"
    (let [dir (temp-git-repo {"README.md" "x\n"})]
      (spit (str dir "/café.py") (str "token = '" gh-token "'\n"))
      (spit (str dir "/ lead.env") "DB_PASSWORD=opaqueVALUE123\n")
      (is (= #{{:file "café.py" :line 1} {:file " lead.env" :line 1}}
             (locations {:code/files []} dir))))))

(deftest ^{:stratum 2} a-file-with-a-nul-byte-is-still-scanned-test
  (testing "git would call it binary and show no hunks; --text keeps its lines"
    (let [dir (temp-git-repo {"README.md" "x\n"})]
      (spit (str dir "/blob.dat") (str "head\u0000\napi_key = '" gh-token "'\n"))
      (is (= #{{:file "blob.dat" :line 2}} (locations {:code/files []} dir))))))
