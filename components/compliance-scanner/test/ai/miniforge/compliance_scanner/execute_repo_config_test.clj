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
(ns ai.miniforge.compliance-scanner.execute-repo-config-test
  "Pins that `execute!` leaves the target repository's config alone. The
   scanner runs in the user's own checkout, whose config file every linked
   worktree shares, so a `git config` write there outlives the run: it
   wrote `commit.gpgsign = false` and turned signing off for every later
   commit in that checkout.

   The test drives `execute!` against a throwaway repository with a bare
   origin and `gh` stubbed. Every git command, the scanner's included,
   runs with a HOME and global config under the temp root and no other
   `GIT_*` variable. That global config demands signing through a signer
   that always fails, so the scanner's commit lands only if it turns
   signing off for that commit."
  (:require [ai.miniforge.compliance-scanner.interface :as scanner]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private target-file
  "Path, relative to the repository root, of the file the plan patches."
  "src/sample.clj")

(def ^{:stratum 0} ^:private original-content
  "(ns sample)\n\n(defn timeout [m] (or (:timeout m) 5000))\n")

(def ^{:stratum 0} ^:private patched-content
  "(ns sample)\n\n(defn timeout [m] (get m :timeout 5000))\n")

(def ^{:stratum 0} ^:private target-line
  "Line of `original-content` the violation names."
  3)

(def ^{:stratum 0} ^:private stub-pr-url
  "What the stubbed `gh pr create` prints."
  "https://example.invalid/pull/1")

(def ^{:stratum 0} ^:private global-config
  "The sandbox's global git config: an identity, and signing demanded
   through `false`, which exits non-zero, so any signed commit fails."
  (str "[user]\n"
       "\tname = Scanner Test\n"
       "\temail = scanner-test@example.invalid\n"
       "[commit]\n"
       "\tgpgsign = true\n"
       "[gpg]\n"
       "\tprogram = false\n"))

(defn- ^{:stratum 0} temp-root!
  []
  (str (Files/createTempDirectory "compliance-scanner-repo-config"
                                  (make-array FileAttribute 0))))

(defn- ^{:stratum 0} delete-tree!
  "Delete `f` and, when it is a real directory, everything under it. A
   symbolic link is deleted as a link, never entered."
  [^File f]
  (when (and (.isDirectory f) (not (Files/isSymbolicLink (.toPath f))))
    (doseq [child (.listFiles f)] (delete-tree! child)))
  (.delete f))

(defn- ^{:stratum 0} sandbox-env
  "`PATH`, plus a HOME and global config under `root`, and nothing else:
   no variable inherited from a git hook can aim git at another
   repository, and the developer's own config is never read."
  [root]
  {"PATH"                (System/getenv "PATH")
   "HOME"                (str root "/home")
   "GIT_CONFIG_GLOBAL"   (str root "/home/.gitconfig")
   "GIT_CONFIG_NOSYSTEM" "1"})

(defn- ^{:stratum 0} git!
  "Run git in `dir` with exactly `env`; throw on a non-zero exit."
  [env dir & args]
  (let [{:keys [exit err] :as result}
        (apply shell/sh "git" "-C" (str dir) (concat args [:env env]))]
    (when-not (zero? exit)
      (throw (ex-info "sandbox git command failed"
                      {:dir (str dir) :args (vec args) :exit exit :err err})))
    result))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} sample-violation
  "One auto-fixable map-access violation at `target-line` of `target-file`."
  []
  (scanner/->violation :std/clojure "210" "Sample rule" target-file target-line
                       "(or (:timeout m) 5000)" "(get m :timeout 5000)"
                       true "Literal default"))

(defn- ^{:stratum 1} sandboxed-sh
  "`shell/sh` as the scanner sees it in this test: every git command runs
   in `env`, and `gh` answers with `stub-pr-url` without running."
  [original env]
  (fn [& args]
    (case (first args)
      "git" (apply original (concat args [:env env]))
      "gh"  {:exit 0 :out (str stub-pr-url "\n") :err ""}
      (apply original args))))

(defn- ^{:stratum 1} config-settings
  "Every entry in `repo`'s own config file except `branch.*`. `git
   checkout -b <branch> origin/main` records the new branch's upstream
   there; that is git's bookkeeping for the branch the scanner creates,
   not a setting."
  [env repo]
  (->> (git! env repo "config" "--file"
             (.getAbsolutePath (File. (File. (str repo) ".git") "config"))
             "--list")
       :out
       str/split-lines
       (remove (fn [line] (str/starts-with? line "branch.")))
       vec))

(defn- ^{:stratum 1} call-with-sandbox
  "Call `f` with a throwaway repository whose `main` holds
   `original-content` at `target-file` and is pushed to a bare origin.
   One temp root holds everything and is deleted afterwards."
  [f]
  (let [root   (temp-root!)
        env    (sandbox-env root)
        origin (str root "/origin.git")
        repo   (str root "/repo")]
    (try
      (.mkdirs (File. (str root "/home")))
      (spit (get env "GIT_CONFIG_GLOBAL") global-config)
      (.mkdirs (File. origin))
      (git! env origin "init" "--quiet" "--bare" "-b" "main")
      (.mkdirs (.getParentFile (File. repo target-file)))
      (git! env repo "init" "--quiet" "-b" "main")
      (spit (File. repo target-file) original-content)
      (git! env repo "add" target-file)
      (git! env repo "-c" "commit.gpgsign=false" "commit" "--quiet" "-m" "seed")
      (git! env repo "remote" "add" "origin" origin)
      (git! env repo "push" "--quiet" "origin" "main")
      (f {:env env :origin origin :repo repo})
      (finally
        (delete-tree! (File. root))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} test-execute-leaves-repo-config-alone
  (call-with-sandbox
   (fn [{:keys [env origin repo]}]
     (let [before (config-settings env repo)
           result (with-redefs [shell/sh (sandboxed-sh shell/sh env)]
                    (scanner/execute! (scanner/plan [(sample-violation)] repo) repo))
           pr     (first (get result :prs))]
       (testing "given a global config that demands signing → the scanner's commit reaches origin unsigned"
         (is (= stub-pr-url (get pr :pr-url)))
         (is (= patched-content
                (get (git! env origin "show"
                           (str "refs/heads/" (get pr :branch) ":" target-file))
                     :out))))
       (testing "given the run → the repository's config file holds the same settings as before"
         (is (= before (config-settings env repo))))))))
