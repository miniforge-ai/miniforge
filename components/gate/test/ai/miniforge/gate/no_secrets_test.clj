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

(defn ^{:stratum 0} capsule-ctx
  "A governed-run context whose worktree path exists only inside the
   \"capsule\": the execute-fn runs each command in DIR, so only the
   execution environment can read it, as in a real capsule."
  [dir]
  {:execution/worktree-path "/workspace/not-on-this-host"
   :execution/executor ::capsule
   :execution/environment-id "env-1"
   :execution/execute-fn (fn [_executor _env-id cmd _opts]
                           (let [r (shell/sh "sh" "-c" cmd :dir dir)]
                             {:ok? true :data {:exit-code (:exit r) :stdout (:out r)}}))})

(def ^{:stratum 0} pem-body
  "Base64-looking key material; not a key."
  "MIIEpAIBAAKCAQEAvxQ8kZ2mNqR7wTc3d4e5f6g7h8i9j0kLmNoPqRsTuVwX")

(defn ^{:stratum 0} pem
  "A PEM private-key block around BODY."
  [body]
  (str "-----BEGIN RSA PRIVATE KEY-----\n" body "\n-----END RSA PRIVATE KEY-----\n"))

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

(deftest ^{:stratum 1} the-capsule-worktree-is-read-through-the-executor-test
  (let [dir (temp-git-repo {"README.md" "x\n"})]
    (testing "a clean governed change passes: the executor's worktree is readable"
      (spit (str dir "/notes.md") "nothing secret\n")
      (is (:passed? (check {:code/files []} (capsule-ctx dir)))))
    (testing "a credential only on the capsule's disk is found there"
      (spit (str dir "/deploy.env") "DB_PASSWORD=opaqueVALUE123\n")
      (is (= [{:file "deploy.env" :line 1}]
             (-> (check {:code/files []} (capsule-ctx dir)) :errors first :locations))))))

(deftest ^{:stratum 1} an-uninspectable-worktree-fails-the-gate-test
  (testing "a supplied worktree git cannot read is a failure, not an artifact-only pass"
    (let [dir (str (Files/createTempDirectory "no-secrets-not-git" (make-array FileAttribute 0)))]
      (is (= :worktree-uninspectable
             (-> (check {:code/files [{:path "a.py" :content "x = 1\n"}]}
                        {:execution/worktree-path dir})
                 :errors first :type)))))
  (testing "with no worktree supplied, the artifact alone is checked"
    (is (:passed? (check {:code/files [{:path "a.py" :content "x = 1\n"}]} {})))))

(deftest ^{:stratum 1} a-credential-committed-then-removed-is-found-test
  (testing "the final tree is clean, but the commit that held the credential would be pushed"
    (let [dir  (temp-git-repo {"src/app.py" "import os\n"})
          base (str/trim (:out (sh! dir "rev-parse" "HEAD")))]
      (spit (str dir "/src/app.py") (str "import os\npassword = \"" opaque "\"\n"))
      (sh! dir "commit" "-qam" "add")
      (let [leaky (str/trim (:out (sh! dir "rev-parse" "HEAD")))]
        (spit (str dir "/src/app.py") "import os\n")
        (sh! dir "commit" "-qam" "remove")
        (let [result (check {:code/files [] :code/base-refs [base]} {:execution/worktree-path dir})]
          (is (= [{:file "src/app.py" :line 2 :commit leaky}] (-> result :errors first :locations)))
          (is (str/includes? (-> result :errors first :message) (subs leaky 0 12))))))))

(deftest ^{:stratum 1} unreadable-history-is-uninspectable-test
  (testing "a git show that fails while scanning history fails the gate"
    (let [dir  (temp-git-repo {"a.py" "x = 1\n"})
          base (str/trim (:out (sh! dir "rev-parse" "HEAD")))
          ctx  (capsule-ctx dir)
          real (:execution/execute-fn ctx)]
      (spit (str dir "/a.py") "x = 2\n")
      (sh! dir "commit" "-qam" "change")
      (is (= :worktree-uninspectable
             (-> (check {:code/files [] :code/base-refs [base]}
                        (assoc ctx :execution/execute-fn
                               (fn [executor env-id cmd opts]
                                 (if (str/includes? cmd "'show'")
                                   {:ok? true :data {:exit-code 128 :stdout ""}}
                                   (real executor env-id cmd opts)))))
                 :errors first :type))))))

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
    (let [head (str/trim (:out (sh! dir "rev-parse" "HEAD")))]
    (testing "a credential committed after the task base is still found, in the file and its commit"
      (is (= #{{:file "src/app.py" :line 2} {:file "src/app.py" :line 2 :commit head}}
             (locations {:code/files [] :code/base-refs [base]} dir))))
    (testing "a resumed workspace's A...HEAD range resolves to the merge base"
      (is (= #{{:file "src/app.py" :line 2} {:file "src/app.py" :line 2 :commit head}}
             (locations {:code/files [] :code/base-refs [(str base "...HEAD")]} dir)))))
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

(deftest ^{:stratum 2} git-defaults-cannot-hide-content-test
  (testing "a textconv driver that masks content does not hide the working change or its commit"
    (let [dir  (temp-git-repo {".gitattributes" "*.env diff=mask\n" "app.env" "x\n"})
          base (str/trim (:out (sh! dir "rev-parse" "HEAD")))]
      (sh! dir "config" "diff.mask.textconv" "sed s/./x/g")
      (spit (str dir "/app.env") "x\nDB_PASSWORD=opaqueVALUE123\n")
      (sh! dir "commit" "-qam" "env")
      (is (= #{{:file "app.env" :line 2}
               {:file "app.env" :line 2 :commit (str/trim (:out (sh! dir "rev-parse" "HEAD")))}}
             (locations {:code/files [] :code/base-refs [base]} dir)))))
  (testing "a file named like pathspec magic is selected literally"
    (let [dir (temp-git-repo {":(literal)deploy.env" "x\n"})]
      (spit (str dir "/:(literal)deploy.env") "x\nDB_PASSWORD=opaqueVALUE123\n")
      (is (= #{{:file ":(literal)deploy.env" :line 2}} (locations {:code/files []} dir))))))

(deftest ^{:stratum 2} a-secret-added-while-merging-is-found-test
  (testing "an edit made while resolving a merge counts, even once a later commit removes it"
    (let [dir  (temp-git-repo {"a.txt" "a\n" "b.txt" "b\n"})
          base (str/trim (:out (sh! dir "rev-parse" "HEAD")))
          main (str/trim (:out (sh! dir "symbolic-ref" "--short" "HEAD")))]
      (sh! dir "checkout" "-qb" "side")
      (spit (str dir "/b.txt") "b\nside\n")
      (sh! dir "commit" "-qam" "side")
      (sh! dir "checkout" "-q" main)
      (spit (str dir "/a.txt") "a\nmain\n")
      (sh! dir "commit" "-qam" "main")
      (sh! dir "merge" "-q" "--no-commit" "side")
      (spit (str dir "/a.txt") (str "a\nmain\napi_key = '" gh-token "'\n"))
      (sh! dir "add" "-A")
      (sh! dir "commit" "-qm" "merge side")
      (let [merge (str/trim (:out (sh! dir "rev-parse" "HEAD")))]
        (spit (str dir "/a.txt") "a\nmain\n")
        (sh! dir "commit" "-qam" "remove")
        (is (= #{{:file "a.txt" :line 3 :commit merge}}
               (locations {:code/files [] :code/base-refs [base]} dir)))))))

(deftest ^{:stratum 2} matches-spanning-lines-are-found-test
  (testing "a PEM body replaced between unchanged BEGIN/END markers"
    (let [dir (temp-git-repo {"key.pem" (pem "placeholder")})]
      (spit (str dir "/key.pem") (pem pem-body))
      (is (= #{{:file "key.pem" :line 2}} (locations {:code/files []} dir)))))
  (testing "a Bearer value folded onto the line after the header"
    (let [dir (temp-git-repo {"api.yaml" "headers:\n  Authorization: Bearer\n"})]
      (spit (str dir "/api.yaml") (str "headers:\n  Authorization: Bearer\n    " opaque opaque "\n"))
      (is (= #{{:file "api.yaml" :line 3}} (locations {:code/files []} dir)))))
  (testing "a multi-line match wholly in unchanged lines stays exempt"
    (let [dir (temp-git-repo {"key.pem" (pem pem-body)})]
      (spit (str dir "/key.pem") (str (pem pem-body) "# rotated yearly\n"))
      (is (= #{} (locations {:code/files []} dir))))))
