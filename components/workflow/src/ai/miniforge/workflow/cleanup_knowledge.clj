;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.cleanup-knowledge
  "Best-effort knowledge cleanup, without swallowing fatal or interrupted causes."
  (:require [ai.miniforge.knowledge.interface :as knowledge]
            [ai.miniforge.resource-cleanup.interface :as cleanup]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} record-error! [errors fields throwable]
  (when-let [fatal (cleanup/fatal-cause throwable)] (cleanup/propagate! fatal))
  (vswap! errors conj (assoc fields :error (ex-message throwable))))

(defn ^{:stratum 0} synthesize-patterns! [knowledge-store]
  (when knowledge-store (knowledge/synthesize-recurring-patterns! knowledge-store)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} promote-one! [knowledge-store errors learning]
  (try+ (knowledge/promote-learning knowledge-store (:zettel/id learning) {})
    (catch Object _ (record-error! errors {:zettel/id (:zettel/id learning)} (:throwable &throw-context)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} promote-mature-learnings! [knowledge-store]
  (let [errors (volatile! [])]
    (try+
      (when knowledge-store
        (doseq [learning (knowledge/list-learnings knowledge-store {:promotable? true})]
          (promote-one! knowledge-store errors learning)))
      (catch Object _ (record-error! errors {} (:throwable &throw-context))))
    @errors))

(comment ::knowledge-cleanup-results)
