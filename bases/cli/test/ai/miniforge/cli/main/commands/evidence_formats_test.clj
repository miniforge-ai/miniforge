;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.cli.main.commands.evidence-formats-test
  (:require [ai.miniforge.cli.main.commands.evidence.formats :as formats]
            [ai.miniforge.cli.messages :as messages]
            [ai.miniforge.evidence-bundle.interface :as evidence]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} rendering-supports-all-advertised-formats
  (let [bundle {:evidence-bundle/id (random-uuid) :text "<script>alert('audit')</script> & \"quoted\""}
        edn (formats/encode bundle "edn")
        json (formats/encode bundle "json")
        html (formats/encode bundle "html")]
    (is (= bundle (evidence/decode-bundle-edn edn)))
    (is (some? (json/parse-string json)))
    (is (.startsWith html "<!doctype html>"))
    (is (.contains html "lang=\"en-US\""))
    (is (.contains html "&lt;script&gt;"))
    (is (not (.contains html "<script>")))
    (is (.contains html "&amp;"))
    (is (nil? (formats/encode bundle "unsupported")))))

(deftest ^{:stratum 0} document-language-tracks-the-rendered-catalog
  (let [catalog (assoc (messages/catalog) :evidence/document-language "fr-FR"
                                         :evidence/header "Dossiers de preuve")]
    (with-redefs [messages/catalog (constantly catalog)]
      (let [html (formats/encode {} "html")]
        (is (.contains html "lang=\"fr-FR\""))
        (is (.contains html "Dossiers de preuve"))))))

(comment
  (clojure.test/run-tests 'ai.miniforge.cli.main.commands.evidence-formats-test))
