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
   [clojure.string :as str]))

(def ^:private default-spec-path
  "Default dogfood target when no explicit spec path is supplied."
  "work/planner-convergence-and-artifact-submission.spec.edn")

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

(defn- prerequisite-status [args]
  (let [spec-path (resolve-spec-path args)
        github-auth (resolve-github-auth)
        anthropic-key (System/getenv "ANTHROPIC_API_KEY")
        openai-key (System/getenv "OPENAI_API_KEY")
        codex-cli (command-available? "codex")
        checks {:spec-exists (fs/exists? spec-path)
                :github-auth (some? github-auth)
                :llm-backend (or codex-cli (some? anthropic-key) (some? openai-key))
                :git-clean (git-clean?)}]
    {:spec-path spec-path
     :github-auth github-auth
     :checks checks
     :backend-source (cond
                       codex-cli :codex-cli
                       anthropic-key :anthropic-api-key
                       openai-key :openai-api-key
                       :else :none)}))

(defn check
  "Validate dogfooding prerequisites. Usage: bb dogfood:check [spec-path]"
  [& args]
  (println "🔍 Checking dogfooding prerequisites...")
  (let [{:keys [spec-path github-auth checks backend-source]}
        (prerequisite-status args)]
    (println "  target-spec:" spec-path)
    (doseq [[check passed?] checks]
      (println (format "  %s %s"
                       (if passed? "✅" "❌")
                       (name check))))
    (println (format "  %s backend-source"
                     (case backend-source
                       :codex-cli "✅ codex-cli"
                       :anthropic-api-key "✅ anthropic-api-key"
                       :openai-api-key "✅ openai-api-key"
                       "❌ none")))
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

(defn dry-run
  "Show the exact command that dogfood would execute.
   Usage: bb dogfood:dry-run [spec-path]"
  [& args]
  (let [{:keys [spec-path github-auth checks backend-source]}
        (prerequisite-status args)
        command (if (= :gh-auth (:source github-auth))
                  (str "env GITHUB_TOKEN=$(gh auth token) bb miniforge run "
                       spec-path)
                  (str "bb miniforge run " spec-path))]
    (println "🤖 Dogfood dry run")
    (println "  spec:" spec-path)
    (println "  backend:" (name backend-source))
    (println "  github-auth:" (or (some-> github-auth :source name) "none"))
    (println)
    (println "Command:")
    (println command)
    (when-not (every? val checks)
      (println)
      (println "Prerequisite failures:")
      (doseq [[check passed?] checks
              :when (not passed?)]
        (println " -" (name check))))))

(defn run
  "Run dogfooding via the development CLI.
   Usage: bb dogfood [spec-path]"
  [& args]
  (let [{:keys [spec-path github-auth checks]}
        (prerequisite-status args)]
    (if (every? val checks)
      (let [proc (p/process (cond-> {:out :inherit
                                     :err :inherit}
                              (:token github-auth)
                              (assoc :extra-env {"GITHUB_TOKEN" (:token github-auth)}))
                            "bb" "miniforge" "run" spec-path)
            {:keys [exit]} @proc]
        (System/exit exit))
      (do
        (check spec-path)
        (System/exit 1)))))
