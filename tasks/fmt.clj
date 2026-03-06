(ns fmt
  (:require
   [babashka.process :as p]
   [clojure.string :as str]))

(defn staged-files []
  (-> (p/sh "git" "diff" "--cached" "--name-only" "--diff-filter=ACM")
      :out
      str/split-lines
      (->> (remove str/blank?))))

(defn staged-by-ext [ext]
  (->> (staged-files)
       (filter (fn [f] (str/ends-with? f ext)))))

(defn md-staged []
  (let [md-files (staged-by-ext ".md")]
    (if (seq md-files)
      (do
        (println "📝 Formatting" (count md-files) "Markdown file(s)...")
        (let [{:keys [exit]} (apply p/sh "markdownlint" "--fix" md-files)]
          (when-not (zero? exit)
            (System/exit exit)))
        ;; Re-add the fixed files to staging
        (doseq [f md-files]
          (p/sh "git" "add" f)))
      (println "✓ No Markdown files to format"))))
