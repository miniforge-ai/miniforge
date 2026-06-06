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

(ns dogfood
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.edn :as edn]
   [clojure.string :as str]))

(def ^:private default-spec-path
  "Default dogfood target when no explicit spec path is supplied.

   Keep this aligned with the top ready dogfood-resilience target in
   `work/QUEUE.md`. Prefer passing an explicit spec path during active
   dogfood sessions so resumed work cannot accidentally restart another
   spec."
  "work/event-log-tool-visibility.spec.edn")

(defn command-available? [cmd]
  (zero? (:exit (p/sh {:continue true :out :discard :err :discard} "which" cmd))))

(defn- gh-authenticated? []
  (and (command-available? "gh")
       (zero? (:exit (p/sh {:continue true :out :discard :err :discard}
                           "gh" "auth" "status")))))

(defn- resolve-github-auth []
  (let [env-token (System/getenv "GITHUB_TOKEN")]
    (cond
      (some? env-token)
      {:source :env
       :token env-token}

      (gh-authenticated?)
      (let [{:keys [exit out]} (p/sh {:continue true :out :string :err :discard}
                                     "gh" "auth" "token")
            token (some-> out str/trim not-empty)]
        (when (and (zero? exit) token)
          {:source :gh-auth
           :token token}))

      :else
      nil)))

(defn- resolve-spec-path [args]
  (or (first args) default-spec-path))

(defn- git-clean? []
  (and (zero? (:exit (p/sh {:continue true :out :discard :err :discard}
                           "git" "diff" "--quiet")))
       (zero? (:exit (p/sh {:continue true :out :discard :err :discard}
                           "git" "diff" "--cached" "--quiet")))))

(def ^:private backends-edn-path
  "Backend metadata, read as a file since the bb task classpath does not
   include the CLI resources dir."
  "bases/cli/resources/config/cli/backends.edn")

(def ^:private user-config-path
  (str (or (System/getenv "MINIFORGE_HOME")
           (str (fs/home) "/.miniforge"))
       "/config.edn"))

(defn- miniforge-home []
  (or (System/getenv "MINIFORGE_HOME")
      (str (fs/home) "/.miniforge")))

(defn- stream-dump-path
  "Per-run timestamped path the LLM client tees every stream line to.

   The 2026-06-06 review-redirect-convergence dogfood (`adhoc-
   2073513324`) hit reviewer-LLM stream-idles whose root cause was
   ambiguous between (a) the provider going genuinely silent on the
   wire and (b) the Claude CLI stream parser dropping tokens during
   long output. Capturing every line the LLM client sees on stdin lets
   the next post-mortem distinguish the two — if the dump grows during
   the silent gap it's parser drift; if it doesn't it's provider
   silence.

   Single append-mode file per run; all phase agents share it (the
   reviewer's section is the one of interest after a stream-idle).
   Path printed before the run so the operator can tail it live."
  []
  (let [ts (.format (java.time.format.DateTimeFormatter/ofPattern
                     "yyyyMMdd-HHmmss-SSS")
                    (java.time.LocalDateTime/now))]
    (str (miniforge-home) "/stream-dumps/dogfood-" ts ".log")))

(defn- read-edn-file [path]
  (when (fs/exists? path)
    (try
      (edn/read-string (slurp (str path)))
      (catch Exception _ nil))))

(defn- configured-backend
  "Resolve the active LLM backend the way a real run does: MINIFORGE_LLM_BACKEND
   overrides everything (matches cli.config/load-merged-config, which applies
   the env var as an override over the file), then the user config file, then
   the shipped default in backends.edn (OpenCode)."
  [backend-config]
  (or (some-> (System/getenv "MINIFORGE_LLM_BACKEND") keyword)
      (get-in (read-edn-file user-config-path) [:llm :backend])
      (get-in backend-config [:backend/defaults :current])))

(defn- backend-command
  "CLI command a backend needs on PATH, or nil if the backend is unknown."
  [backend-config backend]
  (get-in backend-config [:backend/specs backend :command]))

(defn- prerequisite-status [args]
  (let [spec-path (resolve-spec-path args)
        github-auth (resolve-github-auth)
        backend-config (read-edn-file backends-edn-path)
        backend (configured-backend backend-config)
        command (backend-command backend-config backend)
        backend-ok? (boolean (and command (command-available? command)))
        checks {:spec-exists (fs/exists? spec-path)
                :github-auth (some? github-auth)
                :llm-backend backend-ok?
                :git-clean (git-clean?)}]
    {:spec-path spec-path
     :github-auth github-auth
     :checks checks
     :backend backend
     :backend-command command
     :backend-ok? backend-ok?}))

(defn check
  "Validate dogfooding prerequisites. Usage: bb dogfood:check [spec-path]"
  [& args]
  (println "🔍 Checking dogfooding prerequisites...")
  (let [{:keys [spec-path github-auth checks backend backend-command backend-ok?]}
        (prerequisite-status args)]
    (println "  target-spec:" spec-path)
    (doseq [[check passed?] checks]
      (println (format "  %s %s"
                       (if passed? "✅" "❌")
                       (name check))))
    (println (format "  %s backend"
                     (if backend-ok?
                       (format "✅ %s (%s)" (name backend) backend-command)
                       (format "❌ %s (%s missing)"
                               (if backend (name backend) "none")
                               (or backend-command "no command")))))
    (println (format "  %s github-auth-source"
                     (case (:source github-auth)
                       :env "✅ env:GITHUB_TOKEN"
                       :gh-auth "✅ gh auth token"
                       "❌ none")))
    (if (every? val checks)
      (do (println "\n✅ Ready for dogfooding!")
          (System/exit 0))
      (do (println "\n⚠️  Fix issues above before dogfooding")
          (System/exit 1)))))

(defn- shell-single-quote
  "Single-quote a path for shell use: any literal `'` becomes `'\\''`
   and the whole value is wrapped. Round-trips spaces, parens, and the
   characters bash treats as metacharacters without surprising the
   operator when they copy/paste the dry-run command."
  [s]
  (str "'" (str/replace s "'" "'\\''") "'"))

(defn dry-run
  "Show the exact command that dogfood would execute.
   Usage: bb dogfood:dry-run [spec-path]"
  [& args]
  (let [{:keys [spec-path github-auth checks backend]}
        (prerequisite-status args)
        dump-path (stream-dump-path)
        ;; The LLM client opens the dump path in append mode but does
        ;; not create the parent dir; the printed command must match
        ;; the `run` fn's behavior (which calls `fs/create-dirs`
        ;; before launching) or a copy/paste would throw on the first
        ;; `open-stream-dump-writer` call.
        mkdir-prefix (str "mkdir -p "
                          (shell-single-quote (str (fs/parent dump-path)))
                          " && ")
        env-prefix (cond-> ""
                     (= :gh-auth (:source github-auth))
                     (str "env GITHUB_TOKEN=$(gh auth token) ")
                     :always
                     (str "MF_STREAM_DUMP="
                          (shell-single-quote dump-path)
                          " "))
        command (str mkdir-prefix
                     env-prefix
                     "bb miniforge run "
                     (shell-single-quote spec-path))]
    (println "🤖 Dogfood dry run")
    (println "  spec:" spec-path)
    (println "  backend:" (if backend (name backend) "none"))
    (println "  github-auth:" (or (some-> github-auth :source name) "none"))
    (println "  stream-dump:" dump-path)
    (println)
    (println "Command:")
    (println command)
    (println)
    (println "Resume interrupted workflows with:")
    (println "bb miniforge resume <workflow-id>")
    (when-not (every? val checks)
      (println)
      (println "Prerequisite failures:")
      (doseq [[check passed?] checks
              :when (not passed?)]
        (println " -" (name check))))))

(defn run
  "Run dogfooding via the development CLI.
   Usage: bb dogfood [spec-path]

   If a previous run checkpointed, resume it with:
     bb miniforge resume <workflow-id>

   Do not restart the same spec unless there is no useful checkpoint."
  [& args]
  (let [{:keys [spec-path github-auth checks]}
        (prerequisite-status args)]
    (if (every? val checks)
      (let [dump-path (stream-dump-path)
            ;; LLM client (`open-stream-dump-writer`) opens the path
            ;; in append mode but does NOT create parent dirs.
            _ (fs/create-dirs (fs/parent dump-path))
            _ (println "📼 stream dump:" dump-path)
            extra-env (cond-> {"MF_STREAM_DUMP" dump-path}
                        (:token github-auth)
                        (assoc "GITHUB_TOKEN" (:token github-auth)))
            proc (p/process {:out :inherit
                             :err :inherit
                             :extra-env extra-env}
                            "bb" "miniforge" "run" spec-path)
            {:keys [exit]} @proc]
        (System/exit exit))
      (do
        (check spec-path)
        (System/exit 1)))))
