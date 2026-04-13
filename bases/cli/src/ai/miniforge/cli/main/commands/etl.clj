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

(ns ai.miniforge.cli.main.commands.etl
  "ETL commands: repo <url>.

   The `etl repo` command clones a repository and runs the ETL pipeline
   to produce a structured data representation for downstream analysis.

   Delegates to ai.miniforge.etl-pipe.interface when available."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as process]
   [clojure.string :as str]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.main.display :as display]))

;------------------------------------------------------------------------------ Layer 0
;; Helpers

(defn- try-etl-interface [fn-sym & args]
  (try
    (when-let [f (requiring-resolve fn-sym)]
      (apply f args))
    (catch Exception _ nil)))

(defn- validate-git-url [url]
  (or (str/starts-with? url "https://")
      (str/starts-with? url "git@")
      (str/starts-with? url "ssh://")
      (str/starts-with? url "http://")))

(defn- git-clone-temp [url]
  (try
    (let [tmp-dir (str (System/getProperty "java.io.tmpdir") "/miniforge-etl-"
                       (System/currentTimeMillis))
          result  (process/sh "git" "clone" "--depth" "1" url tmp-dir)]
      (if (zero? (:exit result))
        {:success? true :path tmp-dir}
        {:success? false :error (str/trim (:err result))}))
    (catch Exception e
      {:success? false :error (ex-message e)})))

(defn- etl-repo-fallback
  "Clone repo and run repo-analyzer as ETL fallback."
  [url]
  (let [clone-result (git-clone-temp url)]
    (if-not (:success? clone-result)
      (display/print-error (str "Clone failed: " (:error clone-result)))
      (let [repo-path (:path clone-result)]
        (try
          (let [analyze-fn (requiring-resolve 'ai.miniforge.cli.repo-analyzer/analyze-repo)
                analysis   (analyze-fn repo-path)]
            (display/print-success "Repository analysis complete (ETL fallback).")
            (println (str "  Technologies: " (pr-str (:technologies analysis))))
            (println (str "  Git host:     " (get analysis :git-host "unknown")))
            (println (str "  Packs:        " (pr-str (:packs analysis))))
            (println)
            (println (display/style "Note:" :foreground :yellow)
                     " Install the etl-pipe component for full ETL support."))
          (catch Exception e
            (display/print-error (str "Analysis failed: " (ex-message e))))
          (finally
            (try (fs/delete-tree repo-path)
                 (catch Exception _ nil))))))))

;------------------------------------------------------------------------------ Layer 1
;; Command implementation

(defn etl-repo-cmd
  "Run the ETL pipeline against a git repository URL.

   Clones the repository, extracts structured metadata (languages, packs,
   dependency graph, symbol index), and persists the result to the artifacts
   directory for use by downstream analysis commands.

   Usage: miniforge etl repo <url>"
  [opts]
  (let [{:keys [url]} opts]
    (if-not url
      (do (display/print-error
           (str "Usage: " (app-config/command-string "etl repo <url>")))
          (System/exit 1))
      (if-not (validate-git-url url)
        (do (display/print-error
             (str "Invalid repository URL: " url
                  "\nExpected format: https://github.com/org/repo or git@github.com:org/repo"))
            (System/exit 1))
        (do
          (display/print-info (str "Running ETL on repository: " url))
          (let [result (try-etl-interface 'ai.miniforge.etl-pipe.interface/etl-repo url opts)]
            (cond
              ;; Component available and successful
              (and result (:success? result))
              (do
                (display/print-success "ETL complete.")
                (when-let [artifacts (:artifacts result)]
                  (println (str "  Artifacts produced: " (count artifacts))))
                (when-let [path (:output-path result)]
                  (println (str "  Output: " path))))

              ;; Component returned error
              (and result (not (:success? result)))
              (display/print-error (str "ETL failed: " (get result :error "unknown error")))

              ;; Component not available — fallback to repo analysis
              :else
              (etl-repo-fallback url))))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (etl-repo-cmd {:url "https://github.com/miniforge-ai/miniforge"})
  :end)
