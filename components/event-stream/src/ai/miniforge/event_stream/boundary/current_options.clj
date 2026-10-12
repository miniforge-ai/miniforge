;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.boundary.current-options
  "Treat unreadable options as invalid without swallowing critical causes."
  (:require [ai.miniforge.event-stream.boundary.critical :as critical]
            [malli.core :as m]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} valid? [schema value]
  (try+
    (m/validate schema value)
    (catch Object _
      (when-let [fatal (critical/cause (:throwable &throw-context))]
        (critical/propagate! fatal))
      false)))

(comment
  ::current-options-validation)
