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

(ns standards
  (:require
   [babashka.process :as p]
   [clojure.string :as str]))

(defn- run-stream! [& args]
  (let [{:keys [exit]} (deref (apply p/process {:out :inherit :err :inherit} args))]
    exit))

(defn- sanitize-user-paths
  "Replace /Users/<name>/... paths with <repo>/ to avoid leaking local paths."
  [text]
  (str/replace text #"/Users/[^/]+/[^\s,\)\]\}\"]*" "<repo>/"))

;; ──────────────────────────────────────────────────────────────────────────────
;; bb standards:pack
;; ──────────────────────────────────────────────────────────────────────────────

(defn pack
  "Compile .standards/ MDC files into a policy pack EDN resource.

   Delegates to the JVM-based mdc-compiler via `clojure -M:dev` because the
   compiler requires Malli (JVM-only). Writes the pack to
   components/phase/resources/packs/miniforge-standards.pack.edn."
  []
  (println "Compiling standards pack from .standards/ ...")
  (let [output-path "components/phase/resources/packs/miniforge-standards.pack.edn"
        ;; Inline Clojure expression that:
        ;; 1. Compiles the standards pack
        ;; 2. Converts java.time.Instant → java.util.Date for #inst serialization
        ;; 3. Pretty-prints the result for readable diffs and valid clj-kondo parsing
        expr (str "(require '[ai.miniforge.policy-pack.mdc-compiler :as c]"
                  "         '[clojure.pprint :as pp])"
                  "(defn instant->date [v]"
                  "  (if (instance? java.time.Instant v)"
                  "    (java.util.Date/from v)"
                  "    v))"
                  "(defn walk-instants [form]"
                  "  (clojure.walk/postwalk instant->date form))"
                  "(let [result (c/compile-standards-pack \".standards\")]"
                  "  (if (:success? result)"
                  "    (do"
                  "      (let [pack (walk-instants (:pack result))]"
                  "        (spit \"" output-path "\""
                  "              (with-out-str (pp/pprint pack))))"
                  "      (println (str \"Compiled \" (:compiled-count result) \" rules\"))"
                  "      (when (seq (:warnings result))"
                  "        (doseq [w (:warnings result)] (println (str \"  Warning: \" w))))"
                  "      (println (str \"  Failed: \" (:failed-count result)))"
                  "      (System/exit 0))"
                  "    (do"
                  "      (println \"Compilation failed:\")"
                  "      (doseq [e (:errors result)] (println (str \"  \" e)))"
                  "      (System/exit 1))))")
        {:keys [exit out err]}
        (p/sh {:out :string :err :string}
              "clojure" "-M:dev" "-e" expr)]
    (when-not (str/blank? err)
      (binding [*out* *err*] (println err)))
    (if (zero? exit)
      (do
        ;; Sanitize local paths from the output file
        (let [content (slurp output-path)
              sanitized (sanitize-user-paths content)]
          (when (not= content sanitized)
            (spit output-path sanitized)
            (println "  Sanitized local paths in output")))
        (when-not (str/blank? out) (println out))
        (println (str "Wrote " output-path)))
      (do
        (when-not (str/blank? out) (println out))
        (println "standards:pack failed with exit code:" exit)
        (System/exit exit)))))

;; ──────────────────────────────────────────────────────────────────────────────
;; bb review
;; ──────────────────────────────────────────────────────────────────────────────

(defn review
  "Run miniforge's compliance scanner on its own repo (dogfood at the standards level).

   Delegates to `bb miniforge scan` with all available policy packs.
   Supports --fix to auto-fix violations: bb review --fix"
  [args]
  (println "Self-review: scanning miniforge repo for standards violations ...")
  (let [fix? (some #{"--fix"} args)
        base-args ["bb" "miniforge" "scan" "."]
        scan-args (cond-> base-args
                    fix? (conj "--execute"))
        exit (apply run-stream! scan-args)]
    (println)
    (if (zero? exit)
      (println "Self-review complete.")
      (do
        (println "Self-review found issues (exit code:" (str exit ")"))
        (System/exit exit)))))
