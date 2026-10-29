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
(ns ai.miniforge.release-executor.git-test
  "Tests for the host-mode (no-executor) git/gh backend used when a local
   `bb dogfood` run has no DAG executor. Covers branch-name safety, the
   real-git stage/commit/branch/diff round-trip, GH_TOKEN injection, and the
   core step-validate-inputs host-mode dispatch."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as process]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.release-executor.core :as core]
   [ai.miniforge.release-executor.git :as git]
   [ai.miniforge.release-executor.shell :as shell]))

;------------------------------------------------------------------------------ Layer 0

;;------------------------------------------------------------------- helpers
(defn- ^{:stratum 0} sh! [dir & args]
  (apply process/shell
         {:dir (str dir) :out :string :err :string :continue true}
         args))

;;------------------------------------------------------ branch-name safety
(deftest ^{:stratum 0} validate-safe-branch-name-test
  (testing "git-legal names are accepted (nil result)"
    (is (nil? (git/validate-safe-branch-name "main")))
    (is (nil? (git/validate-safe-branch-name "release/task-99c14ec5")))
    (is (nil? (git/validate-safe-branch-name "feature_1.2.3"))))
  (testing "blank / option-flag / metacharacter names are rejected"
    (doseq [bad ["" "-rf" "--force" "a b" "a;rm -rf /" "$(whoami)" "a`b`"]]
      (let [r (git/validate-safe-branch-name bad)]
        (is (and (map? r) (not (:success? r)))
            (str "expected failure for branch name: " (pr-str bad)))))))

;;-------------------------------------------- GH_TOKEN injection (parity)
(deftest ^{:stratum 0} gh-token-injection-test
  (testing "create-pr! injects GH_TOKEN via :extra-env when a token is present"
    (let [seen (atom nil)]
      (with-redefs [shell/run-bounded (fn [args opts] (apply process/shell opts args))
                    process/shell (fn [opts & _]
                                    (reset! seen opts)
                                    {:exit 0 :out "https://github.com/o/r/pull/7" :err ""})]
        (git/create-pr! "/tmp/wt" {:title "t" :body "b" :base-branch "main"} "sekret"))
      (is (= "sekret" (get-in @seen [:extra-env "GH_TOKEN"])))))
  (testing "create-pr! omits :extra-env when no token (inherits ambient env)"
    (let [seen (atom :unset)]
      (with-redefs [shell/run-bounded (fn [args opts] (apply process/shell opts args))
                    process/shell (fn [opts & _]
                                    (reset! seen opts)
                                    {:exit 0 :out "https://github.com/o/r/pull/7" :err ""})]
        (git/create-pr! "/tmp/wt" {:title "t" :body "b" :base-branch "main"} nil))
      (is (nil? (:extra-env @seen)))))
  (testing "create-pr! rejects an unsafe base branch without shelling out"
    (let [called (atom false)]
      (with-redefs [shell/run-bounded (fn [args opts] (apply process/shell opts args))
                    process/shell (fn [& _] (reset! called true) {:exit 0 :out "" :err ""})]
        (let [r (git/create-pr! "/tmp/wt" {:title "t" :body "b" :base-branch "-x"} "tok")]
          (is (not (:success? r)))
          (is (false? @called) "no gh process is spawned for an unsafe base")))))
  (testing "check-gh-auth! injects GH_TOKEN on the auth-status call"
    (let [calls (atom [])]
      (with-redefs [shell/run-bounded (fn [args opts] (apply process/shell opts args))
                    process/shell (fn [opts & args]
                                    (swap! calls conj {:opts opts :args (vec args)})
                                    (if (= "which" (first args))
                                      {:exit 0 :out "/usr/bin/gh" :err ""}
                                      {:exit 0 :out "Logged in to github.com account me (X)" :err ""}))]
        (git/check-gh-auth! "tok-123"))
      (let [auth (first (filter #(= "gh" (first (:args %))) @calls))]
        (is (= "tok-123" (get-in auth [:opts :extra-env "GH_TOKEN"])))))))

(deftest ^{:stratum 0} force-push-injects-token-test
  (testing "force-push! passes GH_TOKEN via :extra-env"
    (let [seen (atom nil)]
      (with-redefs [shell/run-bounded (fn [args opts] (apply process/shell opts args))
                    process/shell (fn [opts & _]
                                    (reset! seen opts)
                                    {:exit 0 :out "" :err ""})]
        (git/force-push! "/tmp/wt" "push-tok"))
      (is (= "push-tok" (get-in @seen [:extra-env "GH_TOKEN"]))))))

(deftest ^{:stratum 0} check-gh-auth-timeout-test
  (testing "a timed-out `gh auth status` keeps :timed-out? and the :timeout anomaly
            instead of reading as an ordinary auth failure"
    (let [anom {:anomaly/type :timeout :anomaly/message "gh auth status timed out"}]
      (with-redefs [process/shell     (fn [& _] {:exit 0 :out "/usr/bin/gh" :err ""})
                    shell/run-bounded (fn [_args _opts]
                                        {:exit 124 :out "" :err "gh auth status timed out"
                                         :timed-out? true :anomaly anom})]
        (let [r (git/check-gh-auth! "tok")]
          (is (false? (:authenticated? r)))
          (is (true? (:timed-out? r)))
          (is (= anom (:anomaly r)))
          (is (= "gh auth status timed out" (:error r))
              "a deadline is not reported as missing auth"))))))

(deftest ^{:stratum 0} commit-changes-rev-parse-failure-test
  (testing "commit-changes! surfaces a rev-parse failure instead of reporting a
            phantom success with a missing sha"
    (with-redefs [process/shell (fn [_opts & args]
                                  (if (= ["git" "rev-parse" "HEAD"] (vec args))
                                    {:exit 1 :out "" :err "fatal: bad revision 'HEAD'"}
                                    {:exit 0 :out "1 file changed" :err ""}))]
      (let [result (git/commit-changes! "/tmp/wt" "feat: add feature")]
        (is (not (:success? result)))
        (is (nil? (:commit-sha result)))
        (is (str/includes? (:error result) "bad revision"))))))

(deftest ^{:stratum 0} exec-bang-exception-includes-output-test
  (testing "exec! includes :output even when process/shell throws"
    (with-redefs [process/shell (fn [& _] (throw (Exception. "spawn failed")))]
      (let [result (git/exec! "/tmp/wt" ["git" "status"])]
        (is (not (:success? result)))
        (is (contains? result :output)
            "an exception path must still carry an :output key, matching the docstring's contract")
        (is (= "" (:output result)))
        (is (str/includes? (:error result) "spawn failed"))))))

;;-------------------------------------------- path traversal + base safety
(deftest ^{:stratum 0} write-file-path-traversal-test
  (let [dir (fs/create-temp-dir {:prefix "git-host-mode-wf-"})]
    (try
      (testing "write-file! writes a normal relative path"
        (is (:success? (git/write-file! dir "docs/pull-requests/p.md" "hi\n")))
        (is (= "hi\n" (slurp (str (fs/path dir "docs/pull-requests/p.md"))))))
      (testing "write-file! rejects a ../ escape and writes nothing outside root"
        (let [r (git/write-file! dir "../escape.md" "nope")]
          (is (not (:success? r)))
          (is (not (fs/exists? (fs/path (fs/parent dir) "escape.md"))))))
      (finally (fs/delete-tree dir)))))

;;-------------------------------------------- push SSH→HTTPS token fallback
(deftest ^{:stratum 0} push-https-fallback-test
  (doseq [[label push!] [["push-branch!" #(git/push-branch! "/tmp/wt" "b" "tok")]
                         ["force-push!" #(git/force-push! "/tmp/wt" "tok")]]]
    (testing (str label ": a failed push with a token retries once over HTTPS, with
                  the token only in the retry's environment")
      (let [pushes (atom [])
            shells (atom [])
            r      (with-redefs [shell/run-bounded
                                 (fn [args opts]
                                   (swap! pushes conj {:args args :opts opts})
                                   (if (= 1 (count @pushes))
                                     {:exit 1 :out "" :err "ssh: permission denied (publickey)"}
                                     {:exit 0 :out "pushed" :err ""}))
                                 process/shell
                                 (fn [_opts & args]
                                   (swap! shells conj (vec args))
                                   (if (= "remote.origin.pushurl" (last args))
                                     {:exit 1 :out "" :err ""}
                                     {:exit 0 :out "git@github.com:o/r.git\n" :err ""}))]
                     (push!))
            [first-push retry] @pushes
            env    (get-in retry [:opts :extra-env])
            h      "https://github.com/o/r.git"]
        (is (:success? r))
        (is (= 2 (count @pushes)))
        (is (nil? (get-in first-push [:opts :extra-env "GIT_CONFIG_COUNT"])))
        (is (= "tok" (get env "GH_TOKEN")))
        (is (= {"GIT_CONFIG_COUNT"   "3"
                "GIT_CONFIG_KEY_0"   (str "http." h ".extraHeader")
                "GIT_CONFIG_VALUE_0" ""
                "GIT_CONFIG_KEY_1"   (str "http." h ".extraHeader")
                "GIT_CONFIG_VALUE_1" (str "Authorization: Basic "
                                          (.encodeToString (java.util.Base64/getEncoder)
                                                           (.getBytes "x-access-token:tok" "UTF-8")))
                "GIT_CONFIG_KEY_2"   (str "url." h ".pushInsteadOf")
                "GIT_CONFIG_VALUE_2" "git@github.com:o/r.git"}
               (dissoc env "GH_TOKEN")))
        (is (not-any? #(some #{"set-url"} %) @shells) "origin is read, never rewritten")
        (is (not-any? #(str/includes? (str/join " " (:args %)) "tok") @pushes)
            "the token is never in argv")))))

(deftest ^{:stratum 0} push-https-fallback-origin-kinds-test
  (let [retry-env (fn [origin token]
                    (let [pushes (atom [])]
                      (with-redefs [shell/run-bounded (fn [_args opts]
                                                        (swap! pushes conj opts)
                                                        {:exit 1 :out "" :err "denied"})
                                    process/shell (fn [_opts & args]
                                                    (if (= "remote.origin.pushurl" (last args))
                                                      {:exit 1 :out "" :err ""}
                                                      {:exit 0 :out origin :err ""}))]
                        (git/push-branch! "/tmp/wt" "b" token))
                      {:pushes (count @pushes)
                       :env    (get-in (second @pushes) [:extra-env])}))]
    (testing "an HTTPS origin gets the header at its full URL"
      (let [{:keys [pushes env]} (retry-env "https://github.com/o/r.git" "tok")]
        (is (= 2 pushes))
        (is (= "http.https://github.com/o/r.git.extraHeader" (get env "GIT_CONFIG_KEY_1")))))
    (testing "an origin with no HTTPS form (a local path) is not retried"
      (is (= 1 (:pushes (retry-env "/srv/git/r.git" "tok")))))
    (testing "no token, no retry"
      (is (= 1 (:pushes (retry-env "git@github.com:o/r.git" nil)))))))

;;------------------------------------------- core host-mode dispatch
(deftest ^{:stratum 0} step-validate-inputs-host-mode-test
  (testing "no executor + no environment-id + worktree present → :host-mode? true"
    (let [r (core/step-validate-inputs {:worktree-path "/tmp/wt" :create-pr? true})]
      (is (:host-mode? r))
      (is (not (core/failed? r)))))
  (testing "sandbox inputs (executor + env-id) do not set :host-mode?"
    (let [r (core/step-validate-inputs {:worktree-path "/tmp/wt"
                                        :executor :some-executor
                                        :environment-id "env-1"
                                        :create-pr? true})]
      (is (not (:host-mode? r)))
      (is (not (core/failed? r)))))
  (testing ":create-pr? true with neither executor nor worktree still fails"
    (is (core/failed? (core/step-validate-inputs {:create-pr? true}))))
  (testing "a blank :worktree-path is not host-mode (babashka :dir \"\" = cwd)"
    (let [r (core/step-validate-inputs {:worktree-path "  " :create-pr? true})]
      (is (not (:host-mode? r)))
      (is (core/failed? r))))
  ;; A java.nio.file.Path — what babashka.fs returns — used to reach
  ;; clojure.string/blank? and throw ClassCastException on the CharSequence
  ;; cast, so validation crashed instead of failing. :worktree-path is
  ;; contractually a String (workspace/core.clj accepts only string?), so a
  ;; Path is rejected the same way a missing one is.
  (testing "a non-string :worktree-path fails validation rather than throwing"
    (let [r (core/step-validate-inputs
             {:worktree-path (java.nio.file.Path/of "/tmp/wt" (into-array String []))
              :create-pr? true})]
      (is (not (:host-mode? r)))
      (is (core/failed? r))
      (is (= :missing-worktree-path (:type (:failure r)))
          "reports the typed missing-worktree-path failure"))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} init-repo!
  "Initialise a throwaway git repo with one seed commit. No `origin` remote
   is added, so origin-relative host-mode calls (create-branch!'s best-effort
   fetch) degrade gracefully — which is itself part of what these tests cover."
  [dir]
  (sh! dir "git" "init" "-q")
  (sh! dir "git" "config" "user.email" "test@example.com")
  (sh! dir "git" "config" "user.name" "Test")
  (sh! dir "git" "config" "commit.gpgsign" "false")
  (spit (str (fs/path dir "README.md")) "seed\n")
  (sh! dir "git" "add" ".")
  (sh! dir "git" "commit" "-q" "-m" "seed"))

;------------------------------------------------------------------------------ Layer 2

;;-------------------------------------------------- real-git round-trip
(deftest ^{:stratum 2} stage-commit-diff-roundtrip-test
  (let [dir (fs/create-temp-dir {:prefix "git-host-mode-"})]
    (try
      (init-repo! dir)
      (testing "exec! runs a command in the worktree"
        (is (:success? (git/exec! dir ["git" "rev-parse" "--is-inside-work-tree"]))))
      (testing "write-file! + stage-files! + commit-changes! round-trip"
        (is (:success? (git/write-file! dir "src/a.clj" "(ns a)\n")))
        (is (:success? (git/stage-files! dir :all)))
        (let [c (git/commit-changes! dir "add a")]
          (is (:success? c))
          (is (re-matches #"[0-9a-f]{7,40}" (:commit-sha c)))))
      (testing "diff-stats counts staged additions"
        (is (:success? (git/write-file! dir "src/b.clj" "(ns b)\n(def x 1)\n")))
        (is (:success? (git/stage-files! dir :all)))
        (let [s (git/diff-stats dir)]
          (is (= 1 (:files s)))
          (is (pos? (:additions s)))))
      (finally (fs/delete-tree dir)))))

(deftest ^{:stratum 2} create-branch-test
  (let [dir (fs/create-temp-dir {:prefix "git-host-mode-branch-"})]
    (try
      (init-repo! dir)
      (testing "create-branch! creates and checks out the branch"
        (let [r (git/create-branch! dir "release/x")]
          (is (:success? r))
          (is (= "release/x" (:branch r)))
          (is (= "release/x"
                 (:output (git/exec! dir ["git" "rev-parse" "--abbrev-ref" "HEAD"]))))))
      (testing "create-branch! rejects an option-injection name before any git call"
        (let [r (git/create-branch! dir "-rf")]
          (is (not (:success? r)))))
      (finally (fs/delete-tree dir)))))

(deftest ^{:stratum 2} range-fns-reject-unsafe-base-test
  (let [dir (fs/create-temp-dir {:prefix "git-host-mode-range-"})]
    (try
      (init-repo! dir)
      (testing "range/ahead functions return nil for an unsafe base (no git call)"
        (is (nil? (git/diff-stats-range dir "-x")))
        (is (nil? (git/count-test-defs-range dir "--upload-pack=evil")))
        (is (nil? (git/commits-ahead-of-base dir "a;rm -rf /"))))
      (finally (fs/delete-tree dir)))))

;;------------------------------------------- remote calls are bounded
(deftest ^{:stratum 2} remote-calls-time-out-test
  (testing "fetch and push to a remote that never answers fail with a :timeout
            anomaly at the deadline, and git's children see prompts disabled"
    (let [dir    (fs/create-temp-dir {:prefix "git-host-mode-hang-"})
          wt     (fs/path dir "wt")
          fake   (str (fs/path dir "hang"))
          prompt (str (fs/path dir "prompt.txt"))]
      (try
        (fs/create-dirs wt)
        (init-repo! wt)
        (sh! dir "git" "init" "-q" "--bare" "remote.git")
        ;; git starts the remote's upload-pack / receive-pack through these
        ;; settings; the stand-in records the prompt setting it inherited and
        ;; then stalls like a dead connection.
        (spit fake (str "#!/bin/sh\necho \"$GIT_TERMINAL_PROMPT\" > " prompt "\nsleep 15\n"))
        (fs/set-posix-file-permissions fake "rwx------")
        (sh! wt "git" "remote" "add" "origin" (str (fs/path dir "remote.git")))
        (sh! wt "git" "config" "remote.origin.uploadpack" fake)
        (sh! wt "git" "config" "remote.origin.receivepack" fake)
        (sh! wt "git" "checkout" "-q" "-b" "release/hang")
        (with-redefs [shell/default-timeout-ms 1000]
          (doseq [[label call] [["fetch-branch!" #(git/fetch-branch! wt "main")]
                                ["push-branch!" #(git/push-branch! wt "release/hang")]]]
            (let [started (System/currentTimeMillis)
                  r       (call)]
              (is (not (:success? r)) label)
              (is (:timed-out? r) label)
              (is (= :timeout (get-in r [:anomaly :anomaly/type])) label)
              (is (< (- (System/currentTimeMillis) started) 10000)
                  (str label " returns at the deadline, not when the remote gives up")))))
        (is (= "0" (str/trim (slurp prompt))) "GIT_TERMINAL_PROMPT=0 reaches git's children")
        (finally (fs/delete-tree dir))))))

;;------------------------------------------- token never reaches git config
(deftest ^{:stratum 2} push-fallback-keeps-token-out-of-git-config-test
  (testing "while the HTTPS token-fallback push hangs, and after it is killed,
            .git/config holds no token and origin is unchanged"
    (let [dir     (fs/create-temp-dir {:prefix "git-host-mode-token-"})
          token   (str "ghp" "_" "fallbackTestToken0000000000000000000")
          ;; The header carries base64("x-access-token:" token); check that too.
          encoded (.encodeToString (java.util.Base64/getEncoder)
                                   (.getBytes (str "x-access-token:" token) "UTF-8"))
          leaked? #(let [c (slurp %)] (or (str/includes? c token) (str/includes? c encoded)))
          origin  "git@github.com:o/r.git"
          config  (str (fs/path dir ".git" "config"))]
      (with-open [proxy (java.net.ServerSocket. 0 1 (java.net.InetAddress/getLoopbackAddress))]
        (try
          (.setSoTimeout proxy 20000)
          (init-repo! dir)
          (sh! dir "git" "checkout" "-q" "-b" "release/token")
          (sh! dir "git" "remote" "add" "origin" origin)
          ;; The SSH push fails at once; the HTTPS retry goes through a local
          ;; proxy that accepts the connection and never answers, so nothing
          ;; reaches github.com.
          (sh! dir "git" "config" "core.sshCommand" "false")
          (sh! dir "git" "config" "http.proxy" (str "http://127.0.0.1:" (.getLocalPort proxy)))
          (with-redefs [shell/default-timeout-ms 2000]
            (let [push (future (git/push-branch! dir "release/token" token))]
              (with-open [_conn (.accept proxy)]
                (is (not (leaked? config))
                    "no token, plain or encoded, in .git/config while the retry is in flight")
                (let [r (deref push 30000 ::stuck)]
                  (is (not= ::stuck r) "the hung retry is killed at the deadline")
                  (is (:timed-out? r))))))
          (is (not (leaked? config)) "no token, plain or encoded, in .git/config afterwards")
          (is (= origin (:output (git/exec! dir ["git" "remote" "get-url" "origin"]))))
          (finally (fs/delete-tree dir)))))))

;;------------------------------------------- token retry scope
(deftest ^{:stratum 2} push-fallback-retry-scope-test
  (let [h      "https://github.com/o/r.git"
        header (str "Authorization: Basic "
                    (.encodeToString (java.util.Base64/getEncoder)
                                     (.getBytes "x-access-token:tok" "UTF-8")))
        github ["remote" "add" "origin" "git@github.com:o/r.git"]
        add    (fn [url] ["config" "--add" "remote.origin.pushurl" url])]
    (doseq [[label setup expected]
            [["SSH github.com origin" [github] :retry]
             ["HTTPS github.com origin" [["remote" "add" "origin" h]] :retry]
             ["single SSH pushurl on github.com"
              [["remote" "add" "origin" h] (add "git@github.com:o/r.git")] :retry]
             ["mirror pushurl"
              [github (add "git@github.com:o/r.git") (add "git@mirror.example.com:o/r.git")]
              :push/token-retry-not-single-target]
             ["mixed remotes"
              [github (add "git@github.com:o/r.git") (add "/srv/git/r.git")]
              :push/token-retry-not-single-target]
             ["pushurl on another host"
              [["remote" "add" "origin" h] (add "git@gitlab.example.com:o/r.git")]
              :push/token-retry-untrusted-host]
             ["equal-length pushInsteadOf"
              [["remote" "add" "origin" h] ["config" "url.git@github.com:o/r.git.pushInsteadOf" h]]
              :push/token-retry-rewritten]
             ["insteadOf alias"
              [["remote" "add" "origin" "gh:o/r.git"] ["config" "url.git@github.com:.insteadOf" "gh:"]]
              :push/token-retry-rewritten]]]
      (testing label
        (let [dir (fs/create-temp-dir {:prefix "git-host-mode-scope-"})]
          (try
            (init-repo! dir)
            (doseq [args setup] (apply sh! dir "git" args))
            (sh! dir "git" "config" (str "http." h ".extraHeader") "Authorization: Basic OLD")
            (let [envs     (atom [])
                  r        (with-redefs [shell/run-bounded (fn [_args opts]
                                                             (swap! envs conj (:extra-env opts))
                                                             {:exit 1 :out "" :err "denied"})]
                             (git/push-branch! dir "main" "tok"))
                  real-git (fn [& args]
                             (str/trim (:out (apply process/shell
                                                    {:dir (str dir) :extra-env (second @envs)
                                                     :out :string :continue true}
                                                    "git" args))))]
              (if (= :retry expected)
                (do (is (= 2 (count @envs)))
                    (is (= h (real-git "remote" "get-url" "--push" "origin"))
                        "the retry pushes to the HTTPS URL")
                    (is (= header (real-git "config" "--get-urlmatch" "http.extraHeader" h))
                        "the token header replaces a repository-scoped one"))
                (do (is (= 1 (count @envs)) "no retry, so the token is sent nowhere")
                    (is (= expected (:token-retry-skipped r)))
                    (is (str/includes? (:error r) "no credential was sent"))
                    (is (str/includes? (:error r) "denied") "the original push error is kept"))))
            (finally (fs/delete-tree dir))))))))
