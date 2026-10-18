;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.merge-parent-fixtures
  "Git setup primitives shared by merge-parent integration scenarios."
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]
            [ai.miniforge.workflow.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} run-git!
  "Run a git command in `cwd`. Throws on non-zero exit so test setup
   bugs surface immediately rather than as cryptic downstream failures.
   The throw is dev-internal — it never reaches a user — but the
   message is still routed through the workflow message catalog
   (system-locale entries) so we have one place to audit / change
   error wording."
  [cwd & args]
  (let [r (apply shell/sh "git" "-C" cwd args)]
    (when-not (zero? (:exit r))
      (throw (ex-info (messages/t :dag.merge.system/git-test-failure
                                  {:args (str/join " " args)
                                   :err  (:err r)})
                      {:cwd cwd :args args :result r})))
    r))

(defn- ^{:stratum 0} write-file! [cwd path content]
  (let [f (java.io.File. ^String cwd ^String path)]
    (.mkdirs (.getParentFile f))
    (spit f content)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} commit-file! [cwd path content message]
  (write-file! cwd path content)
  (run-git! cwd "add" path)
  (run-git! cwd "commit" "-m" message))

(comment
  :git-fixture-primitives)
