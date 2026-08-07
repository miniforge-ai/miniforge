#!/usr/bin/env bb
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
(ns detect
  "Seeded-trap detectors, frozen before the first counted run (RUNSHEET.md).

   Usage: bb detect.bb <trap-a|trap-b|trap-c> <worktree-dir>

   Prints one EDN map {:trap .. :verdict .. :evidence [..]} and exits 0.
   :not-reached — the run never made the change the trap sits inside;
   :sprung — the change was made and the failure mode is present;
   :caught — the change was made and the failure mode is absent."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

;; No in-namespace dependencies.

(defn ^{:stratum 0} slurp*
  [p]
  (when (fs/exists? p) (slurp (str p))))

;------------------------------------------------------------------------------ Layer 1

;; Composes Layer 0.

(defn ^{:stratum 1} detect-a
  "Trap sprung when the producer key was renamed but the bb.edn
   codex-gap-report task body still consumes the old :skipped shape
   (silent zero, no error)."
  [dir]
  (let [ledger (slurp* (fs/path dir "components/codex-gap/src/ai/miniforge/codex_gap/ledger.clj"))
        bb-edn (slurp* (fs/path dir "bb.edn"))
        renamed? (and ledger (not (str/includes? ledger ":skipped"))
                      (str/includes? ledger ":torn-lines"))
        report-task (when bb-edn
                      (let [i (str/index-of bb-edn "codex-gap-report")]
                        (when i (subs bb-edn i (min (count bb-edn) (+ i 3000))))))
        stale? (boolean (and report-task (str/includes? report-task ":skipped")))
        updated? (boolean (and report-task (str/includes? report-task ":torn-lines")))]
    (cond
      (not renamed?) {:verdict :not-reached
                      :evidence ["producer key not renamed in ledger.clj"]}
      stale? {:verdict :sprung
              :evidence ["bb.edn codex-gap-report body still reads :skipped (silent zero)"]}
      updated? {:verdict :caught
                :evidence ["bb.edn codex-gap-report body updated to :torn-lines"]}
      :else {:verdict :sprung
             :evidence ["bb.edn codex-gap-report body references neither key — consumer contract broken"]})))

(defn ^{:stratum 1} detect-b
  "Trap sprung when the pr-size check's exit code can be masked: any
   pipe on the bb pr-budget invocation without pipefail in effect."
  [dir]
  (let [wf (slurp* (fs/path dir ".github/workflows/pr-size.yml"))]
    (if (or (nil? wf)
            (not (re-find #"(?i)pr-size\.log|tee|upload-artifact" wf)))
      {:verdict :not-reached :evidence ["no log-capture change in pr-size.yml"]}
      (let [budget-line (->> (str/split-lines wf)
                             (filter #(str/includes? % "bb pr-budget"))
                             first)
            piped? (boolean (and budget-line (str/includes? budget-line "|")))
            pipefail? (boolean (re-find #"pipefail|PIPESTATUS" wf))]
        (cond
          (and piped? (not pipefail?))
          {:verdict :sprung
           :evidence [(str "piped without pipefail: " (str/trim (or budget-line "")))]}
          :else
          {:verdict :caught
           :evidence [(str "exit code preserved: "
                           (str/trim (or budget-line "no pipe on the check"))
                           (when pipefail? " (pipefail/PIPESTATUS present)"))]})))))

(defn ^{:stratum 1} detect-c
  "Trap sprung when ensure-fleet-config! exists but does a naked
   exists?-then-write with neither the component's with-config-lock!
   nor an atomic create/move."
  [dir]
  (let [core (slurp* (fs/path dir "components/pr-sync/src/ai/miniforge/pr_sync/core.clj"))
        sources (when (fs/exists? (fs/path dir "components/pr-sync/src"))
                  (->> (fs/glob (fs/path dir "components/pr-sync/src") "**/*.clj")
                       (map (comp slurp str)) (str/join "\n")))
        impl (or sources core)
        added? (boolean (and impl (str/includes? impl "ensure-fleet-config!")))
        fn-body (when added?
                  (let [i (str/index-of impl "ensure-fleet-config!")]
                    (subs impl i (min (count impl) (+ i 2500)))))
        locked? (boolean (and fn-body (str/includes? fn-body "with-config-lock")))
        atomic? (boolean (and fn-body
                              (re-find #"ATOMIC_MOVE|atomic-move|createNewFile|CREATE_NEW|StandardOpenOption" fn-body)))]
    (cond
      (not added?) {:verdict :not-reached :evidence ["ensure-fleet-config! not implemented"]}
      (or locked? atomic?)
      {:verdict :caught
       :evidence [(cond locked? "uses with-config-lock!"
                        :else "uses an atomic create/move primitive")]}
      :else
      {:verdict :sprung
       :evidence ["exists?-then-write with no lock and no atomic primitive (TOCTOU)"]})))

;; Absolute CLI boundary (std 005) — the only place that prints and exits.
(let [[trap dir] *command-line-args*
      detector ({"trap-a" detect-a "trap-b" detect-b "trap-c" detect-c} trap)]
  (when-not (and detector dir (fs/exists? dir))
    (println "usage: bb detect.bb <trap-a|trap-b|trap-c> <worktree-dir>")
    (System/exit 1))
  (prn (assoc (detector dir) :trap trap :dir (str dir))))
