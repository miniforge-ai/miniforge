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
(ns test-runner-test
  "Pins `test-runner`: which project tests `bb test:integration` runs and
   that project test code stays off the miniforge project's :paths; and
   that git started through `run-stream!` cannot write to the repository a
   hook's environment names, with every launcher starting its processes
   from the sanitizer's output (real git against a decoy under tmpdir)."
  (:require [ai.miniforge.bb-test-runner.interface :as bb-test-runner]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [test-runner :as sut]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} previously-unrun
  "Project tests the hand-written list never named."
  ["ai.miniforge.current-event-publication-integration-test"
   "ai.miniforge.event-journal-integration-test"
   "ai.miniforge.event-journal-ownership-integration-test"
   "ai.miniforge.file-durability.integration-test"
   "ai.miniforge.phase-opsv.recovery-receipts-integration-test"
   "ai.miniforge.workflow.chain-current-lifecycle-integration-test"
   "ai.miniforge.workflow.chain-edge-integration-test"
   "ai.miniforge.workflow.chain-publication-integration-test"
   "ai.miniforge.workflow.chain-required-integration-test"
   "ai.miniforge.workflow.chain-resources-integration-test"
   "ai.miniforge.workflow.chain-retention-integration-test"
   "ai.miniforge.workflow.opsv-phase-completion-integration-test"
   "ai.miniforge.workflow.partial-terminal-receipt-integration-test"])

(defn ^{:stratum 0} test-files-under
  "Namespace names of every *_test.clj file under `dir`, built from each
   relative path's segments (Path iteration, so either separator)."
  [dir]
  (->> (fs/glob dir "**_test.clj")
       (map #(->> (fs/components (fs/relativize dir %))
                  (map (fn [segment]
                         (-> (str segment)
                             (str/replace #"\.clj$" "")
                             (str/replace "_" "-"))))
                  (str/join ".")))
       set))

(defn ^{:stratum 0} project-deps
  [project]
  (edn/read-string (slurp (str "projects/" project "/deps.edn"))))

(deftest ^{:stratum 0} discovers-miniforge-core-project-test-test
  (is (= ["ai.miniforge.workflow.kernel-loader-integration-test"]
         (sut/discovered-project-tests "miniforge-core"))))

(deftest ^{:stratum 0} known-failing-skips-name-real-tests-test
  (testing "each skip names a discovered test, so a rename cannot leave a dead entry"
    (let [discovered (set (sut/discovered-project-tests "miniforge"))]
      (doseq [ns-name (keys sut/known-failing-project-tests)]
        (is (contains? discovered ns-name) ns-name))))
  (testing "each skip carries a reason"
    (is (every? (comp not str/blank?) (vals sut/known-failing-project-tests)))))

(deftest ^{:stratum 0} external-tests-are-skipped-by-default-test
  (testing "the skip form drops :test from ^:external vars only"
    (let [test-ns (create-ns 'test-runner-test.external-fixture)
          external (intern test-ns (with-meta 'calls-backend {:external true :test (fn [])}) nil)
          local (intern test-ns (with-meta 'stays-local {:test (fn [])}) nil)]
      (try
        (binding [*out* (java.io.StringWriter.)]
          (load-string (sut/external-test-skip-form ["test-runner-test.external-fixture"])))
        (is (nil? (:test (meta external))))
        (is (some? (:test (meta local))))
        (finally
          (remove-ns 'test-runner-test.external-fixture)))))
  (testing "every development test that needs the real claude CLI is tagged ^:external"
    (require 'ai.miniforge.cli-integration-test 'ai.miniforge.dogfood-test)
    (doseq [v ['ai.miniforge.cli-integration-test/cli-availability-test
               'ai.miniforge.cli-integration-test/claude-simple-prompt-test
               'ai.miniforge.cli-integration-test/claude-system-prompt-test
               'ai.miniforge.cli-integration-test/claude-code-generation-test
               'ai.miniforge.dogfood-test/real-docstring-addition-test
               'ai.miniforge.dogfood-test/real-test-generation-test
               'ai.miniforge.dogfood-test/real-bug-fix-test]]
      (is (:external (meta (resolve v))) (str v)))))

(def ^{:stratum 0} ^:private stripped-probe
  "A `user.name` only the stripped write sets: the config file holding it
   is the one that write reached."
  "stripped-probe")

(def ^{:stratum 0} ^:private unstripped-probe
  "A `user.name` only the unstripped control write sets."
  "unstripped-probe")

(def ^{:stratum 0} ^:private sanitized-env
  "Stands in for the sanitizer's output: a process started with exactly
   this environment got it from `sanitize-git-worktree-env`."
  {"SANITIZED" "yes"})

(def ^{:stratum 0} ^:private launchers
  "Every function in `test-runner` that starts a process, by task name."
  {"test:poly"        sut/poly-all
   "test:integration" sut/integration
   "test:dev"         sut/development
   "test:conformance" sut/conformance
   "test:graalvm"     sut/graalvm
   "test:precommit"   sut/precommit-smoke})

(defn- ^{:stratum 0} base-env
  "`PATH` and `HOME` and nothing else. The tests lay `GIT_*` variables
   over this, so each one they hand to git points at the decoy — never at
   the real checkout, even when this JVM itself runs under a git hook."
  []
  (select-keys (into {} (System/getenv)) ["PATH" "HOME"]))

(defn- ^{:stratum 0} config-file
  [repo]
  (str (fs/path repo ".git" "config")))

(defn- ^{:stratum 0} git!
  "Run git in `dir` with exactly `env`; throw on a non-zero exit."
  [env dir & args]
  (let [{:keys [exit err] :as result}
        (apply p/sh {:dir (str dir) :env env :out :string :err :string}
               "git" args)]
    (when-not (zero? exit)
      (throw (ex-info "git fixture command failed"
                      {:dir (str dir) :args (vec args) :exit exit :err err})))
    result))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} discovers-every-miniforge-project-test-test
  (let [discovered (set (sut/discovered-project-tests "miniforge"))]
    (testing "the namespaces the hand-written list missed are discovered"
      (doseq [ns-name previously-unrun]
        (is (contains? discovered ns-name) ns-name)))
    (testing "every *_test.clj under test/ and integration/ is discovered"
      (is (= (into (test-files-under "projects/miniforge/test")
                   (test-files-under "projects/miniforge/integration"))
             discovered)))
    (testing "e2e tests (real claude CLI / Docker) are not"
      (is (empty? (filter (test-files-under "projects/miniforge/e2e")
                          discovered))))))

(deftest ^{:stratum 1} discovers-every-development-test-test
  (let [discovered (set (sut/discovered-development-tests))]
    (testing "every *_test.clj under development/test is discovered"
      (is (= (test-files-under "development/test") discovered)))
    (testing "including this namespace and the task tests CI never ran"
      (doseq [ns-name ["test-runner-test" "build-test" "deps-resources-test"
                       "ai.miniforge.dogfood-test"]]
        (is (contains? discovered ns-name) ns-name)))
    (testing "support namespaces are not run as tests"
      (is (not (contains? discovered "ai.miniforge.test-harness"))))))

(deftest ^{:stratum 1} project-test-code-is-not-on-paths-test
  (let [{:keys [paths aliases]} (project-deps "miniforge")]
    (testing "no test directory is a classpath root of the project's builds"
      (is (empty? (filter #{"test" "integration" "e2e"} paths))))
    (testing "integration tests stay out of :test, which Polylith runs under poly test :all"
      (is (empty? (filter #{"test" "integration" "e2e"}
                          (get-in aliases [:test :extra-paths])))))
    (testing "the :integration alias carries the test directories"
      (is (= ["test" "integration"]
             (get-in aliases [:integration :extra-paths]))))
    (testing "the :e2e alias carries test/, whose support namespaces e2e requires"
      (is (= ["test" "e2e"]
             (get-in aliases [:e2e :extra-paths]))))))

(defn- ^{:stratum 1} hook-env
  "`base-env` plus the repository-binding variables a git hook fired in
   `repo` hands its children. A hook does not export `GIT_CONFIG`; it is
   here because it redirects a `git config` write by itself, with or
   without `GIT_DIR`."
  [repo]
  (let [git-dir (str (fs/path repo ".git"))]
    (merge (base-env)
           {"GIT_DIR"        git-dir
            "GIT_INDEX_FILE" (str (fs/path git-dir "index"))
            "GIT_PREFIX"     ""
            "GIT_CONFIG"     (config-file repo)})))

(defn- ^{:stratum 1} started-envs
  "The `:env` of each process `launcher` starts, in order. The sanitizer
   returns `sanitized-env` and both process entry points only record, so
   nothing runs and a zero exit keeps the launcher off `System/exit`."
  [launcher]
  (let [envs   (atom [])
        record (fn [opts] (swap! envs conj (get opts :env)))]
    (with-redefs [bb-test-runner/sanitize-git-worktree-env (constantly sanitized-env)
                  p/process (fn [opts & _] (record opts) (delay {:exit 0}))
                  p/sh      (fn [opts & _] (record opts) {:exit 0 :out ""})]
      (with-out-str (launcher)))
    @envs))

(defn- ^{:stratum 1} call-with-decoy-and-target
  "Call `f` with two fresh repositories under one temp root: `:decoy`
   stands in for the checkout a hook fired in, `:target` for the temp
   directory a test runs its own git in. The root is deleted afterwards."
  [f]
  (let [root   (fs/create-temp-dir {:prefix "miniforge-test-runner-"})
        decoy  (str (fs/create-dir (fs/path root "decoy")))
        target (str (fs/create-dir (fs/path root "target")))]
    (try
      (git! (base-env) decoy "init" "--quiet")
      (git! (base-env) target "init" "--quiet")
      (f {:decoy decoy :target target})
      (finally
        (fs/delete-tree root)))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} test-run-stream-keeps-git-off-the-repository-a-hook-names
  (call-with-decoy-and-target
   (fn [{:keys [decoy target]}]
     (let [env    (hook-env decoy)
           before (slurp (config-file decoy))]
       (testing "given a hook environment aimed at the decoy → a config write started through run-stream! lands in its own directory"
         (is (zero? (sut/run-stream! {:dir target :env env}
                                     "git" "config" "user.name" stripped-probe)))
         (is (str/includes? (slurp (config-file target)) stripped-probe))
         (is (= before (slurp (config-file decoy)))))
       (testing "given the same environment unstripped → the same write reaches the decoy"
         (git! env target "config" "user.name" unstripped-probe)
         (is (str/includes? (slurp (config-file decoy)) unstripped-probe)))))))

(deftest ^{:stratum 2} test-every-launcher-starts-its-processes-with-the-sanitized-environment
  (doseq [[task launcher] launchers]
    (testing (str "given " task " with no environment passed → each process it starts gets the sanitizer's output")
      (let [envs (started-envs launcher)]
        (is (seq envs))
        (is (every? (fn [env] (= sanitized-env env)) envs))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'test-runner-test)

  :leave-this-here)
