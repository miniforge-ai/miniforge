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
   [babashka.process :as p]))

(defn command-available? [cmd]
  (zero? (:exit (p/sh {:continue true :out :discard :err :discard} "which" cmd))))

(defn check []
  (println "🔍 Checking dogfooding prerequisites...")
  (let [github-token (System/getenv "GITHUB_TOKEN")
        anthropic-key (System/getenv "ANTHROPIC_API_KEY")
        openai-key (System/getenv "OPENAI_API_KEY")
        codex-cli (command-available? "codex")
        git-clean (zero? (:exit (p/sh {:continue true} "git" "diff" "--quiet")))
        checks {:github-token (some? github-token)
                :llm-backend (or codex-cli (some? anthropic-key) (some? openai-key))
                :git-clean git-clean}]
    (doseq [[check passed?] checks]
      (println (format "  %s %s"
                       (if passed? "✅" "❌")
                       (name check))))
    (println (format "  %s backend-source"
                     (cond
                       codex-cli "✅ codex-cli"
                       anthropic-key "✅ anthropic-api-key"
                       openai-key "✅ openai-api-key"
                       :else "❌ none")))
    (if (every? val checks)
      (do (println "\n✅ Ready for dogfooding!")
          (System/exit 0))
      (do (println "\n⚠️  Fix issues above before dogfooding")
          (System/exit 1)))))
