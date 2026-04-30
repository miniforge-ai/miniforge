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

(ns ai.miniforge.bb-platform.core
  "Cross-platform OS detection, package install routing, and platform
   self-check used by `bb` tasks across miniforge umbrella repos.

   Layer 0: pure data (OS detection, install plans, check assembly).
   Layer 1: side-effecting fns (process queries, plan execution, print).

   Plans are returned as data so callers can inspect them in tests
   without shelling out, and so the same logic drives both
   `bb install:foo` and dry-run / printing modes."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Pure: OS detection

(defn detect-os
  "Map a Java `os.name` string to one of #{:macos :linux :windows}.
   Unknown / blank inputs default to :linux."
  [os-name]
  (let [n (str/lower-case (or os-name ""))]
    (cond (or (str/includes? n "mac")
              (str/includes? n "darwin"))    :macos
          (str/includes? n "windows")        :windows
          :else                              :linux)))

;------------------------------------------------------------------------------ Layer 0
;; Pure: install / upgrade plans

(def manual-install-hints
  "Per-package fallback instructions when no automated installer is
   available on the current OS."
  {"clojure"      "https://clojure.org/guides/install_clojure"
   "clj-kondo"    "https://github.com/clj-kondo/clj-kondo/blob/master/doc/install.md"
   "markdownlint" "npm install -g markdownlint-cli"
   "poly"         "https://polylith.gitbook.io/polylith/install"
   "bb"           "curl -sLO https://raw.githubusercontent.com/babashka/babashka/master/install && chmod +x install && ./install --static"
   "java"         "Install Temurin 21 via your distro package manager (apt/dnf/pacman)"
   "podman"       "Linux: install via your distro package manager (apt install podman, dnf install podman, pacman -S podman). Windows: scoop install podman. See https://podman.io/getting-started/installation"})

(defn formula->cmd
  "Extract the canonical command name from a brew-style formula path
   (the trailing path segment): `\"borkdude/brew/clj-kondo\"` → `\"clj-kondo\"`."
  [formula]
  (last (str/split formula (re-pattern "/"))))

(defn install-plan
  "Return a plan map for installing `formula` on `os`.

   Required keys in the input map:
     :formula     — brew-style formula path (e.g., \"clojure/tools/clojure\")
     :os          — one of :macos, :linux, :windows
     :installed?  — whether the resolved cmd is already on PATH

   Result keys:
     :action      — :run | :hint | :skip
     :package     — canonical command name
     :command     — vector of args (when :action = :run)
     :hint        — manual-install instruction string (when :action = :hint)
     :reason      — :already-installed (when :action = :skip)"
  [{:keys [formula os installed?]}]
  (let [cmd (formula->cmd formula)]
    (cond
      installed?
      {:action :skip :package cmd :reason :already-installed}

      (= os :macos)
      {:action :run :package cmd :command ["brew" "install" formula]}

      (= os :windows)
      {:action :run :package cmd :command ["scoop" "install" cmd]}

      :else
      {:action :hint :package cmd
       :hint   (get manual-install-hints cmd
                    "Install via your distro package manager")})))

(defn upgrade-plan
  "Plan to upgrade `formula` on `os`. Same shape as `install-plan` but
   without `:installed?` (always attempts the upgrade on Mac/Windows)."
  [{:keys [formula os]}]
  (let [cmd (formula->cmd formula)]
    (cond
      (= os :macos)
      {:action :run :package cmd :command ["brew" "upgrade" formula]}

      (= os :windows)
      {:action :run :package cmd :command ["scoop" "update" cmd]}

      :else
      {:action :hint :package cmd
       :hint   (get manual-install-hints cmd
                    "Upgrade via your distro package manager")})))

(defn install-plan-java
  "Java has its own install path on Mac (cask) and Windows (different
   scoop bucket name) — handled separately from the brew-formula path."
  [{:keys [os installed?]}]
  (cond
    installed?
    {:action :skip :package "java" :reason :already-installed}

    (= os :macos)
    {:action :run :package "java"
     :command ["brew" "install" "--cask" "temurin@21"]}

    (= os :windows)
    {:action :run :package "java"
     :command ["scoop" "install" "temurin21-jdk"]}

    :else
    {:action :hint :package "java" :hint (get manual-install-hints "java")}))

(defn install-plan-markdownlint
  "markdownlint-cli is npm-distributed everywhere; on Mac brew wraps it,
   elsewhere we go straight through npm if it is available."
  [{:keys [os installed? npm-installed?]}]
  (cond
    installed?
    {:action :skip :package "markdownlint" :reason :already-installed}

    (= os :macos)
    {:action :run :package "markdownlint"
     :command ["brew" "install" "markdownlint-cli"]}

    npm-installed?
    {:action :run :package "markdownlint"
     :command ["npm" "install" "-g" "markdownlint-cli"]}

    :else
    {:action :hint :package "markdownlint"
     :hint   "Install Node.js + npm, then: npm install -g markdownlint-cli"}))

;------------------------------------------------------------------------------ Layer 0
;; Pure: check report assembly

(defn check
  "Build a platform check report from supplied facts. Pure assembly so
   tests don't have to spelunk into System/getProperties."
  [{:keys [os-name os-arch shell java-version bb-version os tools]}]
  {:os-name      os-name
   :os-arch      os-arch
   :os           os
   :shell        shell
   :java-version java-version
   :bb-version   bb-version
   :tools        tools})

;------------------------------------------------------------------------------ Layer 1
;; Side-effecting: PATH lookup, process props, plan execution, printing

(defn os-key
  "Detect the OS of the current process via System/getProperty."
  []
  (detect-os (System/getProperty "os.name")))

(defn installed?
  "True if `cmd` resolves on PATH. Uses `babashka.fs/which` so it works
   on Unix (which) and Windows (where) without branching."
  [cmd]
  (some? (fs/which cmd)))

(defn execute-plan!
  "Run the side effects described by a plan. Returns the plan, augmented
   with :result on :run actions so callers can check exit codes."
  [plan]
  (case (:action plan)
    :skip
    (do (println "  ✓" (:package plan) "already installed")
        plan)

    :run
    (let [cmd (:command plan)]
      (println "  ⬇️  Running:" (str/join " " cmd))
      (let [result (apply p/shell {:continue true} cmd)]
        (assoc plan :result {:exit (:exit result)})))

    :hint
    (do (println "  ⚠️  No automated installer for" (:package plan)
                 "on this platform")
        (println "      " (:hint plan))
        plan)))

(defn install!
  "Convenience: build and execute an install plan for `formula` on the
   current OS, skipping if the resolved command is already on PATH."
  [formula]
  (execute-plan!
   (install-plan {:formula    formula
                  :os         (os-key)
                  :installed? (installed? (formula->cmd formula))})))

(defn upgrade!
  "Convenience: build and execute an upgrade plan for `formula` on the
   current OS."
  [formula]
  (execute-plan!
   (upgrade-plan {:formula formula :os (os-key)})))

;------------------------------------------------------------------------------ Layer 1
;; Podman machine bootstrap (macOS only — Linux Podman is daemonless)

(defn- podman-machine-running?
  "True when `podman machine list` reports a running default machine.
   Returns false when Podman is missing entirely."
  []
  (when (installed? "podman")
    (let [{:keys [exit out]} (p/sh "podman" "machine" "list" "--format" "{{.Running}}")]
      (and (zero? exit) (str/includes? (or out "") "true")))))

(defn- podman-machine-exists?
  "True when at least one Podman machine is registered (running or not)."
  []
  (when (installed? "podman")
    (let [{:keys [exit out]} (p/sh "podman" "machine" "list" "--format" "{{.Name}}")]
      (and (zero? exit) (seq (str/trim (or out "")))))))

(defn init-podman-machine!
  "Mac-only courtesy: ensure a Podman machine is initialised and running.

   Linux Podman is daemonless and needs no machine. On macOS Podman runs
   inside a Linux VM (`podman machine`); without one, `podman run` fails.

   No-op when Podman is not installed (caller already failed earlier),
   when not on macOS, or when a machine is already running."
  []
  (cond
    (not= :macos (os-key))
    (println "  ✓ podman machine: skipping (not macOS — Podman is daemonless on Linux)")

    (not (installed? "podman"))
    (println "  ⚠️  podman machine: skipping (podman not on PATH)")

    (podman-machine-running?)
    (println "  ✓ podman machine: already running")

    (podman-machine-exists?)
    (do (println "  ⬇️  Starting existing podman machine...")
        (p/shell {:continue true} "podman" "machine" "start"))

    :else
    (do (println "  ⬇️  Initialising default podman machine (this can take a minute)...")
        (p/shell {:continue true} "podman" "machine" "init")
        (println "  ⬇️  Starting podman machine...")
        (p/shell {:continue true} "podman" "machine" "start"))))

(defn check-current
  "Gather facts about the current process and assemble a check report
   covering the listed `tool-cmds`."
  [tool-cmds]
  (let [props (System/getProperties)]
    (check {:os-name      (.get props "os.name")
            :os-arch      (.get props "os.arch")
            :shell        (or (System/getenv "SHELL")
                              (System/getenv "ComSpec")
                              "(unknown)")
            :java-version (.get props "java.version")
            :bb-version   (System/getProperty "babashka.version")
            :os           (os-key)
            :tools        (into {} (map (fn [c] [c (installed? c)])) tool-cmds)})))

(defn print-check!
  "Print a human-friendly view of a check report. Includes per-OS notes
   when relevant (currently: scoop bucket reminders on Windows)."
  [report]
  (println "Miniforge platform check")
  (println "────────────────────────")
  (println (str "  OS       : " (:os-name report) " (" (name (:os report)) ")"))
  (println "  Arch     :" (:os-arch report))
  (println "  Shell    :" (:shell report))
  (println "  Java     :" (:java-version report))
  (println "  Babashka :" (:bb-version report))
  (println)
  (println "Required tools:")
  (doseq [[cmd present?] (:tools report)]
    (println (if present?
               (str "  ✓ " cmd)
               (str "  ✗ " cmd " (not on PATH)"))))
  (when (= (:os report) :windows)
    (println)
    (println "Windows notes:")
    (println "  • bb bootstrap routes brew installs to scoop on Windows.")
    (println "  • Ensure these scoop buckets are added:")
    (println "      scoop bucket add scoop-clojure https://github.com/littleli/scoop-clojure")
    (println "      scoop bucket add extras")
    (println "  • run-demo.sh requires Git Bash or WSL2.")
    (println "  • See docs/platform-support.md for the full status matrix.")))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (detect-os "Mac OS X")
  (detect-os "Windows 11")
  (install-plan {:formula "borkdude/brew/clj-kondo" :os :windows :installed? false})
  (check-current ["java" "git" "definitely-missing"])
  :leave-this-here)
