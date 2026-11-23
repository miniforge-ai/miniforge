;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.run-control-fixtures
  (:require [ai.miniforge.phase-opsv.interface :as opsv]
            [ai.miniforge.phase-opsv.pr-fixtures :as f]
            [clojure.java.io :as io]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} wait-ms
  "Finite synchronization guard; tests deliver latches instead of relying on scheduling delays." 5000)

(defn ^{:stratum 0} await-work [entered finish]
  (deliver entered true)
  @finish)

(defn ^{:stratum 0} with-run [supervisor abort! test-fn]
  (let [{:keys [ctx runtime calls]} (f/setup)
        handles (opsv/register-run-control! supervisor (:execution/id ctx)
                   (:authority-directory runtime) abort!)
        controlled (merge runtime handles)]
    (try+
      (test-fn (assoc-in ctx [:execution/opts :opsv/pr-execution] controlled) controlled calls)
      (finally
        (doseq [file (reverse (file-seq (io/file (get-in runtime [:provider :directory]))))]
          (io/delete-file file))))))
