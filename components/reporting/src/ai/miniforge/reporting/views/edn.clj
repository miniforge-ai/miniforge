(ns ai.miniforge.reporting.views.edn
  "EDN data rendering.

   Provides pretty-printed EDN output for structured data."
  (:require [clojure.pprint]))

;------------------------------------------------------------------------------ Layer 0
;; EDN rendering

(defn render-edn
  "Render arbitrary data as pretty-printed EDN.

   Args:
     data - Any Clojure data structure

   Returns:
     Pretty-printed EDN string."
  [data]
  (with-out-str
    (clojure.pprint/pprint data)))

(comment
  ;; Test EDN rendering
  (println (render-edn {:status :running
                        :workflows [{:id 1 :name "Pipeline"}
                                   {:id 2 :name "Deploy"}]
                        :meta {:cycle 42}}))

  (println (render-edn [1 2 3 4 5]))

  :end)
