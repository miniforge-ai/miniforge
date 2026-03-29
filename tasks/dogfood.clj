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
