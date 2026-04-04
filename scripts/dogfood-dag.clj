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

#!/usr/bin/env bb
;; Run miniforge on itself - dogfooding with DAG orchestration

#_{:clj-kondo/ignore [:duplicate-require]}
(require '[clojure.edn :as edn])
#_{:clj-kondo/ignore [:duplicate-require]}
(require '[clojure.java.io :as io])

(defn load-dag-tasks
  "Load task definitions from EDN file"
  [file-path]
  (-> file-path slurp edn/read-string))

(defn print-dag-summary
  "Print a summary of the DAG"
  [dag]
  (println "\n🤖 MINIFORGE DOGFOODING SESSION")
  (println "================================")
  (println "DAG ID:" (:dag-id dag))
  (println "Description:" (:description dag))
  (println "\nTasks:")
  (doseq [task (:tasks dag)]
    (println (format "  [%s] %s" (:task/id task) (:description task)))
    (when (seq (:task/deps task))
      (println (format "    ├─ Depends on: %s" (pr-str (:task/deps task)))))
    (println (format "    └─ Files: %s" (pr-str (:files task))))))

(defn check-prerequisites
  "Check if we have everything needed to run"
  []
  (println "\n🔍 Checking prerequisites...")
  (let [checks {:git-repo (.exists (io/file ".git"))}]
    (doseq [[check passed?] checks]
      (println (format "  %s %s"
                      (if passed? "✅" "❌")
                      (name check))))
    (println "\n💡 Note: Using CLI backends (claude code, gh)")
    (println "   Ensure you're authenticated:")
    (println "   - gh auth status")
    (println "   - claude --version")
    (every? val checks)))

(defn print-monitoring-info
  []
  (println "\n📊 MONITORING")
  (println "=============")
  (println "Watch progress:")
  (println "  tail -f logs/dogfood-*.log")
  (println "\nManual intervention:")
  (println "  - Check PRs: gh pr list")
  (println "  - Cancel: Ctrl+C (graceful shutdown)")
  (println "  - Force stop: kill <pid>"))

(defn start-dag-execution
  "Start the DAG execution with task-executor"
  [dag]
  (println "\n🚀 Starting DAG execution...")
  (println "NOTE: This is a dry-run simulation for now.")
  (println "Full execution requires:")
  (println "  1. LLM backend wired into task-executor")
  (println "  2. Executor (Docker/K8s/worktree) configured")
  (println "  3. Event monitoring setup")
  (println "\nNext steps to enable full dogfooding:")
  (println "  1. Wire LLM backend (task-1 can do this!)")
  (println "  2. Configure executor in bb.edn")
  (println "  3. Run: bb dogfood --dag dogfood-tasks.edn")

  {:status :dry-run
   :tasks-planned (count (:tasks dag))})

(defn -main [& args]
  (let [dag-file (or (first args) "dogfood-tasks.edn")
        dag (load-dag-tasks dag-file)]

    (print-dag-summary dag)

    (when (check-prerequisites)
      (print-monitoring-info)
      (let [result (start-dag-execution dag)]
        (println "\n✨ Dry-run complete!")
        (println "Result:" (pr-str result))
        (System/exit 0)))

    (println "\n⚠️  Missing prerequisites - cannot run yet")
    (System/exit 1)))

;; Run if invoked directly
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
