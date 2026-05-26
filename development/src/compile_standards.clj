;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0
;;
;; Standalone script invoked by `clojure -M:dev -m compile-standards`.
;; Not loaded by bb — requires JVM for Malli dependency.

(ns compile-standards
  (:require
   [clojure.pprint :as pprint]
   [clojure.string :as str]
   [clojure.walk :as walk]
   [ai.miniforge.policy-pack.mdc-compiler :as mdc-compiler]))

(def ^:private output-path
  "components/phase/resources/packs/miniforge-standards.pack.edn")

(def ^:private standards-dir
  "Source directory of compiled .mdc rules — the miniforge-standards submodule,
   relocated from .standards/ to standards/miniforge/ (PR #975)."
  "standards/miniforge")

(defn- instant->date
  "Convert java.time.Instant to java.util.Date for #inst EDN serialization."
  [v]
  (if (instance? java.time.Instant v)
    (java.util.Date/from v)
    v))

(defn- sanitize-user-paths
  "Replace absolute /Users/<name>/… paths with <repo>/ inside a STRING VALUE,
   so local developer paths don't leak into the committed pack.

   Sanitizing the parsed DATA (here) rather than the serialized EDN text is
   essential: the previous text-level regex (in tasks/standards.clj) consumed
   the escaping backslash of an embedded `\\\"/Users/…\\\"` and left an
   unescaped quote, producing an EDN file that `clojure.edn/read-string` (the
   runtime pack loader) could not parse at all. pprint re-escapes the
   sanitized strings correctly."
  [v]
  (if (string? v)
    ;; Delimiter-aware: scrub only the absolute path TOKEN. The trailing class
    ;; stops at whitespace and at quotes/backticks/brackets/parens/commas/
    ;; semicolons so an embedded example like `:worktree "/Users/x/repo"` keeps
    ;; its closing quote (a greedy \S* ate it, mangling the example) and any
    ;; surrounding EDN/markdown structure stays intact.
    (str/replace v #"/Users/[^/\s]+/[^\s\"'`(){}\[\],;]*" "<repo>/")
    v))

(defn -main [& _args]
  (let [result (mdc-compiler/compile-standards-pack standards-dir)]
    (if (:success? result)
      (let [pack (walk/postwalk (comp sanitize-user-paths instant->date)
                                (:pack result))]
        (spit output-path (with-out-str (pprint/pprint pack)))
        (println (str "Compiled " (:compiled-count result) " rules"))
        (when (seq (:warnings result))
          (doseq [w (:warnings result)]
            (println (str "  Warning: " w))))
        (println (str "  Failed: " (:failed-count result)))
        (println (str "  Wrote " output-path)))
      (do
        (println "Compilation failed:")
        (doseq [e (:errors result)]
          (println (str "  " e)))
        (System/exit 1)))))
