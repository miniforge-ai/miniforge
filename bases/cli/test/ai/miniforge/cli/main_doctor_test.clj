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
(ns ai.miniforge.cli.main-doctor-test
  "`mf doctor` checks git (and its identity), gh (and its login), the
   configured backend's CLI and, for governed execution, the container
   runtime, and exits 1 when a required one fails.

   Driven through `-main` with fake tools: each test writes sh scripts
   into a temp dir and makes it the only directory `fs/which` searches."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.cli.config :as config]
   [ai.miniforge.cli.main :as sut]
   [ai.miniforge.cli.main.commands.shared :as shared]
   [ai.miniforge.dag-executor.interface :as dag]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} fake-git
  "case \"$1\" in
  --version) echo 'git version 2.99.0' ;;
  config) case \"$2\" in
            user.name) echo 'Test User' ;;
            user.email) echo 'test@example.com' ;;
          esac ;;
esac")

(def ^{:stratum 0} fake-git-without-email
  "case \"$1\" in
  --version) echo 'git version 2.99.0' ;;
  config) case \"$2\" in
            user.name) echo 'Test User' ;;
            *) exit 1 ;;
          esac ;;
esac")

(def ^{:stratum 0} fake-git-with-empty-email
  "case \"$1\" in
  --version) echo 'git version 2.99.0' ;;
  config) case \"$2\" in
            user.name) echo 'Test User' ;;
            user.email) echo '' ;;
          esac ;;
esac")

(def ^{:stratum 0} fake-gh
  "case \"$1\" in
  --version) echo 'gh version 2.99.0' ;;
  auth) echo 'Logged in to github.com account test' ;;
esac")

(def ^{:stratum 0} fake-gh-logged-out
  "case \"$1\" in
  --version) echo 'gh version 2.99.0' ;;
  auth) echo 'You are not logged into any GitHub hosts.' >&2; exit 1 ;;
esac")

(def ^{:stratum 0} fake-backend-cli
  "echo '1.2.3'")

(def ^{:stratum 0} runtime-found
  (dag/ok {:descriptor      {}
           :kind            :podman
           :selection       :auto-probe
           :runtime-version "5.0.0"
           :probed          []}))

(def ^{:stratum 0} runtime-missing
  (dag/err :runtime/none-available "no usable container runtime" {:probed []}))

(defn- ^{:stratum 0} write-tool!
  [bin tool-name body]
  (let [path (fs/path bin tool-name)]
    (spit (str path) (str "#!/bin/sh\n" body "\n"))
    (fs/set-posix-file-permissions path "rwxr-xr-x")))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} healthy-tools
  {"git"      fake-git
   "gh"       fake-gh
   "opencode" fake-backend-cli})

(defn- ^{:stratum 1} run-doctor
  "Run `mf doctor <args>` with `tools` (name -> sh body) as the only
   programs on PATH, `cfg` as the loaded config and `runtime` as the
   runtime selection. Returns {:exit code-or-:no-exit :out str :err str}."
  [{:keys [tools cfg runtime]} & args]
  (let [bin        (fs/create-temp-dir)
        real-which fs/which
        only-bin   (fn
                     ([program] (real-which program {:paths [(str bin)]}))
                     ([program opts] (real-which program (assoc opts :paths [(str bin)]))))
        exit       (atom :no-exit)
        out        (java.io.StringWriter.)
        err        (java.io.StringWriter.)]
    (doseq [[tool-name body] tools]
      (write-tool! bin tool-name body))
    (try
      (with-redefs [fs/which           only-bin
                    config/load-config (constantly cfg)
                    dag/select-runtime (constantly runtime)
                    shared/exit!       (fn [code] (reset! exit code))]
        (binding [*out* out
                  *err* err]
          (apply sut/-main "doctor" args)))
      (finally
        (fs/delete-tree bin)))
    {:exit @exit
     :out  (str out)
     :err  (str err)}))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} healthy-setup-passes-test
  (testing "all required tools present and logged in: no failing exit"
    (let [{:keys [exit out]} (run-doctor {:tools   healthy-tools
                                          :cfg     {:llm {:backend :opencode}}
                                          :runtime runtime-found})]
      (is (= :no-exit exit) out)
      (is (str/includes? out "git version 2.99.0"))
      (is (str/includes? out "Test User"))
      (is (str/includes? out "test@example.com"))
      (is (str/includes? out "GitHub CLI login"))
      (is (str/includes? out "LLM backend opencode"))
      (is (str/includes? out "1.2.3")))))

(deftest ^{:stratum 2} logged-out-gh-fails-test
  (testing "gh present but `gh auth status` fails: exit 1 with the login hint"
    (let [{:keys [exit out err]} (run-doctor {:tools   (assoc healthy-tools "gh" fake-gh-logged-out)
                                              :cfg     {:llm {:backend :opencode}}
                                              :runtime runtime-found})]
      (is (= 1 exit))
      (is (str/includes? out "You are not logged into any GitHub hosts."))
      (is (str/includes? out "gh auth login"))
      (is (str/includes? err "1 required check(s) failed.")))))

(deftest ^{:stratum 2} missing-gh-fails-test
  (testing "gh not on PATH: exit 1"
    (let [{:keys [exit out]} (run-doctor {:tools   (dissoc healthy-tools "gh")
                                          :cfg     {:llm {:backend :opencode}}
                                          :runtime runtime-found})]
      (is (= 1 exit))
      (is (str/includes? out "gh not found on PATH")))))

(deftest ^{:stratum 2} configured-backend-cli-is-checked-test
  (testing "the configured backend's CLI is checked, not a fixed `claude`"
    (let [{:keys [exit out]} (run-doctor {:tools   (assoc healthy-tools "claude" fake-backend-cli)
                                          :cfg     {:llm {:backend :codex}}
                                          :runtime runtime-found})]
      (is (= 1 exit))
      (is (str/includes? out "LLM backend codex"))
      (is (str/includes? out "codex not found on PATH")))))

(deftest ^{:stratum 2} backend-flag-overrides-config-test
  (testing "--backend checks that backend instead of the configured one"
    (let [{:keys [exit out]} (run-doctor {:tools   (assoc healthy-tools "claude" fake-backend-cli)
                                          :cfg     {:llm {:backend :codex}}
                                          :runtime runtime-found}
                                         "--backend" "claude")]
      (is (= :no-exit exit) out)
      (is (str/includes? out "LLM backend claude"))
      (is (not (str/includes? out "codex"))))))

(deftest ^{:stratum 2} http-backend-needs-no-cli-test
  (testing "a backend that calls an HTTP API needs no CLI on PATH"
    (let [{:keys [exit out]} (run-doctor {:tools   (dissoc healthy-tools "opencode")
                                          :cfg     {:llm {:backend :anthropic-api}}
                                          :runtime runtime-found})]
      (is (= :no-exit exit) out)
      (is (str/includes? out "LLM backend anthropic-api")))))

(deftest ^{:stratum 2} missing-git-identity-fails-test
  (testing "git present but user.email unset: exit 1 with the fix"
    (let [{:keys [exit out]} (run-doctor {:tools   (assoc healthy-tools "git" fake-git-without-email)
                                          :cfg     {:llm {:backend :opencode}}
                                          :runtime runtime-found})]
      (is (= 1 exit))
      (is (str/includes? out "git config --global user.email")))))

(deftest ^{:stratum 2} empty-git-identity-fails-test
  (testing "user.email set to \"\": `git config` exits 0 but git refuses to commit, so exit 1"
    (let [{:keys [exit out]} (run-doctor {:tools   (assoc healthy-tools "git" fake-git-with-empty-email)
                                          :cfg     {:llm {:backend :opencode}}
                                          :runtime runtime-found})]
      (is (= 1 exit))
      (is (str/includes? out "set but empty"))
      (is (str/includes? out "git config --global user.email")))))

(deftest ^{:stratum 2} runtime-required-only-for-governed-mode-test
  (testing "no container runtime: optional for local execution"
    (let [{:keys [exit out]} (run-doctor {:tools   healthy-tools
                                          :cfg     {:llm {:backend :opencode}}
                                          :runtime runtime-missing})]
      (is (= :no-exit exit) out)
      (is (str/includes? out "no usable container runtime"))))
  (testing "no container runtime: required for --execution-mode governed"
    (let [{:keys [exit out]} (run-doctor {:tools   healthy-tools
                                          :cfg     {:llm {:backend :opencode}}
                                          :runtime runtime-missing}
                                         "--execution-mode" "governed")]
      (is (= 1 exit))
      (is (str/includes? out "no usable container runtime")))))

(deftest ^{:stratum 2} unknown-option-is-refused-test
  (testing "a misspelled option exits 2 with usage instead of being ignored"
    (let [{:keys [exit err]} (run-doctor {:tools   healthy-tools
                                          :cfg     {:llm {:backend :opencode}}
                                          :runtime runtime-found}
                                         "--bakend" "claude")]
      (is (= 2 exit))
      (is (str/includes? err "--bakend")))))
