;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.cli.main.commands.evidence.formats
  "Render the already-validated immutable bundle; perform no filesystem effects."
  (:require [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.cli.messages :as messages]
            [hiccup2.core :as html]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} encode-html [bundle]
  (let [title (messages/t :evidence/header)
        content (evidence/encode-bundle-edn bundle)]
    (str "<!doctype html>"
         (html/html [:html [:head [:meta {:charset "utf-8"}] [:title title]]
                     [:body [:h1 title] [:pre content]]]))))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} encoders
  {"edn" evidence/encode-bundle-edn
   "json" artifact/encode-content-json
   "html" encode-html})

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} encode [bundle format]
  (when-let [encoder (get encoders format)]
    (encoder bundle)))

(comment
  (encode {} "html"))
