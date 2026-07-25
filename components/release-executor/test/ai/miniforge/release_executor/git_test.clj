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
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.release-executor.core :as core]
   [ai.miniforge.release-executor.git :as git]))

;;------------------------------------------------------------------- helpers
(defn- sh! [dir & args]
  (apply process/shell
         {:dir (str dir) :out :string :err :string :continue true}
         args))

(defn- init-repo!
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

;;------------------------------------------------------ branch-name safety
(deftest validate-safe-branch-name-test
  (testing "git-legal names are accepted (nil result)"
    (is (nil? (git/validate-safe-branch-name "main")))
    (is (nil? (git/validate-safe-branch-name "release/task-99c14ec5")))
    (is (nil? (git/validate-safe-branch-name "feature_1.2.3"))))
  (testing "blank / option-flag / metacharacter names are rejected"
    (doseq [bad ["" "-rf" "--force" "a b" "a;rm -rf /" "$(whoami)" "a`b`"]]
      (let [r (git/validate-safe-branch-name bad)]
        (is (and (map? r) (not (:success? r)))
            (str "expected failure for branch name: " (pr-str bad)))))))

;;-------------------------------------------------- real-git round-trip
(deftest stage-commit-diff-roundtrip-test
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

(deftest create-branch-test
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

;;-------------------------------------------- GH_TOKEN injection (parity)
(deftest gh-token-injection-test
  (testing "create-pr! injects GH_TOKEN via :extra-env when a token is present"
    (let [seen (atom nil)]
      (with-redefs [process/shell (fn [opts & _]
                                    (reset! seen opts)
                                    {:exit 0 :out "https://github.com/o/r/pull/7" :err ""})]
        (git/create-pr! "/tmp/wt" {:title "t" :body "b" :base-branch "main"} "sekret"))
      (is (= "sekret" (get-in @seen [:extra-env "GH_TOKEN"])))))
  (testing "create-pr! omits :extra-env when no token (inherits ambient env)"
    (let [seen (atom :unset)]
      (with-redefs [process/shell (fn [opts & _]
                                    (reset! seen opts)
                                    {:exit 0 :out "https://github.com/o/r/pull/7" :err ""})]
        (git/create-pr! "/tmp/wt" {:title "t" :body "b" :base-branch "main"} nil))
      (is (nil? (:extra-env @seen)))))
  (testing "create-pr! rejects an unsafe base branch without shelling out"
    (let [called (atom false)]
      (with-redefs [process/shell (fn [& _] (reset! called true) {:exit 0 :out "" :err ""})]
        (let [r (git/create-pr! "/tmp/wt" {:title "t" :body "b" :base-branch "-x"} "tok")]
          (is (not (:success? r)))
          (is (false? @called) "no gh process is spawned for an unsafe base")))))
  (testing "check-gh-auth! injects GH_TOKEN on the auth-status call"
    (let [calls (atom [])]
      (with-redefs [process/shell (fn [opts & args]
                                    (swap! calls conj {:opts opts :args (vec args)})
                                    (if (= "which" (first args))
                                      {:exit 0 :out "/usr/bin/gh" :err ""}
                                      {:exit 0 :out "Logged in to github.com account me (X)" :err ""}))]
        (git/check-gh-auth! "tok-123"))
      (let [auth (first (filter #(= "gh" (first (:args %))) @calls))]
        (is (= "tok-123" (get-in auth [:opts :extra-env "GH_TOKEN"])))))))

(deftest force-push-injects-token-test
  (testing "force-push! passes GH_TOKEN via :extra-env"
    (let [seen (atom nil)]
      (with-redefs [process/shell (fn [opts & _]
                                    (reset! seen opts)
                                    {:exit 0 :out "" :err ""})]
        (git/force-push! "/tmp/wt" "push-tok"))
      (is (= "push-tok" (get-in @seen [:extra-env "GH_TOKEN"]))))))

;;-------------------------------------------- path traversal + base safety
(deftest write-file-path-traversal-test
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

(deftest range-fns-reject-unsafe-base-test
  (let [dir (fs/create-temp-dir {:prefix "git-host-mode-range-"})]
    (try
      (init-repo! dir)
      (testing "range/ahead functions return nil for an unsafe base (no git call)"
        (is (nil? (git/diff-stats-range dir "-x")))
        (is (nil? (git/count-test-defs-range dir "--upload-pack=evil")))
        (is (nil? (git/commits-ahead-of-base dir "a;rm -rf /"))))
      (finally (fs/delete-tree dir)))))

;;-------------------------------------------- push SSH→HTTPS fallback
(deftest push-branch-https-fallback-test
  (testing "push fails with a token → rewrite SSH origin to https-with-token, retry, restore"
    (let [calls   (atom [])
          push-n  (atom 0)]
      (with-redefs [process/shell
                    (fn [_opts & args]
                      (let [a (vec args)]
                        (swap! calls conj a)
                        (cond
                          (= "push" (second a))
                          (do (swap! push-n inc)
                              (if (= 1 @push-n)
                                {:exit 1 :out "" :err "ssh: permission denied (publickey)"}
                                {:exit 0 :out "pushed" :err ""}))
                          (= ["git" "remote" "get-url" "origin"] a)
                          {:exit 0 :out "git@github.com:o/r.git" :err ""}
                          :else {:exit 0 :out "" :err ""})))]
        (let [r        (git/push-branch! "/tmp/wt" "b" "tok")
              set-urls (filter #(= ["git" "remote" "set-url"] (vec (take 3 %))) @calls)]
          (is (:success? r) "second push over https succeeds")
          (is (= 2 (count set-urls)) "origin is set to https-with-token then restored")
          (is (re-find #"x-access-token:tok@github\.com/o/r\.git" (str (last (first set-urls))))
              "temporary origin embeds the token over https")
          (is (= "git@github.com:o/r.git" (last (second set-urls)))
              "original ssh origin is restored"))))))

(deftest push-branch-restore-failure-fails-loud-test
  (testing "restore of origin failing after the https fallback fails loud with a scrub hint"
    (let [push-n (atom 0) seturl-n (atom 0)]
      (with-redefs [process/shell
                    (fn [_opts & args]
                      (let [a (vec args)]
                        (cond
                          (= "push" (second a))
                          (do (swap! push-n inc)
                              (if (= 1 @push-n)
                                {:exit 1 :out "" :err "ssh: denied"}
                                {:exit 0 :out "ok" :err ""}))
                          (= ["git" "remote" "get-url" "origin"] a)
                          {:exit 0 :out "git@github.com:o/r.git" :err ""}
                          (= ["git" "remote" "set-url"] (vec (take 3 a)))
                          (do (swap! seturl-n inc)
                              (if (= 1 @seturl-n)
                                {:exit 0 :out "" :err ""}          ; set to https ok
                                {:exit 1 :out "" :err "config locked"})) ; restore fails
                          :else {:exit 0 :out "" :err ""})))]
        (let [r (git/push-branch! "/tmp/wt" "b" "tok")]
          (is (not (:success? r)) "restore failure makes the overall result a failure")
          (is (re-find #"Scrub it" (:error r)) "surfaces the scrub command")
          (is (true? (:push-succeeded? r)) "records that the push itself succeeded"))))))

;;-------------------------------------------- force-push shares the fallback
(deftest force-push-https-fallback-test
  (testing "force-push over SSH fails with a token → rewrite to https-with-token, retry, restore"
    (let [calls  (atom [])
          push-n (atom 0)]
      (with-redefs [process/shell
                    (fn [_opts & args]
                      (let [a (vec args)]
                        (swap! calls conj a)
                        (cond
                          (= "push" (second a))
                          (do (swap! push-n inc)
                              (if (= 1 @push-n)
                                {:exit 1 :out "" :err "ssh: permission denied (publickey)"}
                                {:exit 0 :out "pushed" :err ""}))
                          (= ["git" "remote" "get-url" "origin"] a)
                          {:exit 0 :out "git@github.com:o/r.git" :err ""}
                          :else {:exit 0 :out "" :err ""})))]
        (let [r        (git/force-push! "/tmp/wt" "tok")
              set-urls (filter #(= ["git" "remote" "set-url"] (vec (take 3 %))) @calls)]
          (is (:success? r) "second force-push over https succeeds")
          (is (= 2 @push-n) "force-push retried once after the SSH failure")
          (is (= 2 (count set-urls)) "origin is set to https-with-token then restored")
          (is (re-find #"x-access-token:tok@github\.com/o/r\.git" (str (last (first set-urls))))
              "temporary origin embeds the token over https")
          (is (= "git@github.com:o/r.git" (last (second set-urls)))
              "original ssh origin is restored"))))))

(deftest force-push-restore-failure-fails-loud-test
  (testing "force-push restore failure after the https fallback fails loud with a scrub hint"
    (let [push-n (atom 0) seturl-n (atom 0)]
      (with-redefs [process/shell
                    (fn [_opts & args]
                      (let [a (vec args)]
                        (cond
                          (= "push" (second a))
                          (do (swap! push-n inc)
                              (if (= 1 @push-n)
                                {:exit 1 :out "" :err "ssh: denied"}
                                {:exit 0 :out "ok" :err ""}))
                          (= ["git" "remote" "get-url" "origin"] a)
                          {:exit 0 :out "git@github.com:o/r.git" :err ""}
                          (= ["git" "remote" "set-url"] (vec (take 3 a)))
                          (do (swap! seturl-n inc)
                              (if (= 1 @seturl-n)
                                {:exit 0 :out "" :err ""}
                                {:exit 1 :out "" :err "config locked"}))
                          :else {:exit 0 :out "" :err ""})))]
        (let [r (git/force-push! "/tmp/wt" "tok")]
          (is (not (:success? r)) "restore failure makes the overall result a failure")
          (is (re-find #"Scrub it" (:error r)) "surfaces the scrub command")
          (is (true? (:push-succeeded? r)) "records that the push itself succeeded"))))))

(deftest force-push-push-and-restore-both-fail-test
  (testing "https retry push AND restore both fail → error carries both, push-succeeded? false"
    (let [push-n (atom 0) seturl-n (atom 0)]
      (with-redefs [process/shell
                    (fn [_opts & args]
                      (let [a (vec args)]
                        (cond
                          (= "push" (second a))
                          (do (swap! push-n inc)
                              (if (= 1 @push-n)
                                {:exit 1 :out "" :err "ssh: denied"}
                                {:exit 128 :out "" :err "remote rejected https"}))
                          (= ["git" "remote" "get-url" "origin"] a)
                          {:exit 0 :out "git@github.com:o/r.git" :err ""}
                          (= ["git" "remote" "set-url"] (vec (take 3 a)))
                          (do (swap! seturl-n inc)
                              (if (= 1 @seturl-n)
                                {:exit 0 :out "" :err ""}
                                {:exit 1 :out "" :err "config locked"}))
                          :else {:exit 0 :out "" :err ""})))]
        (let [r (git/force-push! "/tmp/wt" "tok")]
          (is (not (:success? r)))
          (is (false? (:push-succeeded? r)) "records that the retry push also failed")
          (is (re-find #"remote rejected https" (:error r)) "includes the retry push error")
          (is (re-find #"config locked" (:error r)) "includes the restore error")
          (is (re-find #"Scrub it" (:error r)) "still surfaces the scrub command"))))))

(deftest force-push-https-setup-failure-test
  (testing "repointing origin to the token URL fails → report it, skip the retry push, no restore"
    (let [push-n (atom 0) seturl-n (atom 0)]
      (with-redefs [process/shell
                    (fn [_opts & args]
                      (let [a (vec args)]
                        (cond
                          (= "push" (second a))
                          (do (swap! push-n inc)
                              {:exit 1 :out "" :err "ssh: denied"})
                          (= ["git" "remote" "get-url" "origin"] a)
                          {:exit 0 :out "git@github.com:o/r.git" :err ""}
                          (= ["git" "remote" "set-url"] (vec (take 3 a)))
                          (do (swap! seturl-n inc)
                              {:exit 1 :out "" :err "config locked"})
                          :else {:exit 0 :out "" :err ""})))]
        (let [r (git/force-push! "/tmp/wt" "tok")]
          (is (not (:success? r)))
          (is (= 1 @push-n) "the retry push is not attempted when the token-URL set-url fails")
          (is (= 1 @seturl-n) "only the token-URL set-url runs; no restore (origin unchanged)")
          (is (false? (:push-succeeded? r)))
          (is (re-find #"could not be applied" (:error r)) "reports the fallback could not be set up")
          (is (re-find #"ssh: denied" (:error r)) "includes the original push error")
          (is (re-find #"config locked" (:error r)) "surfaces the set-url error"))))))

;;------------------------------------------- core host-mode dispatch
(deftest step-validate-inputs-host-mode-test
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
      (is (core/failed? r)))))
