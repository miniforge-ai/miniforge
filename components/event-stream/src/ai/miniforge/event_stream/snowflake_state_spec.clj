;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.snowflake-state-spec
  "Mutable generator-state shape used only at stream admission boundaries."
  (:require [ai.miniforge.event-stream.snowflake :as snowflake]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} Worker [:int {:min 0 :max (dec snowflake/max-workers)}])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} State
  [:map [:last-ts [:int {:min -1}]] [:last-seq [:int {:min -1 :max snowflake/max-seq}]]
   [:worker-id Worker]])

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} valid? [value]
  (and (instance? clojure.lang.Atom value) (m/validate State @value)))

(comment
  (valid? nil))
