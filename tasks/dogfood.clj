(ns dogfood
  (:require
   [babashka.process :as p]))

(defn check []
  (println "🔍 Checking dogfooding prerequisites...")
  (let [github-token (System/getenv "GITHUB_TOKEN")
        anthropic-key (System/getenv "ANTHROPIC_API_KEY")
        git-clean (zero? (:exit (p/sh {:continue true} "git" "diff" "--quiet")))
        checks {:github-token (some? github-token)
                :anthropic-key (some? anthropic-key)
                :git-clean git-clean}]
    (doseq [[check passed?] checks]
      (println (format "  %s %s"
                       (if passed? "✅" "❌")
                       (name check))))
    (if (every? val checks)
      (do (println "\n✅ Ready for dogfooding!")
          (System/exit 0))
      (do (println "\n⚠️  Fix issues above before dogfooding")
          (System/exit 1)))))
