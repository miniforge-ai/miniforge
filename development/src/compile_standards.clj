;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0
;;
;; Standalone script invoked by `clojure -M:dev -m compile-standards`.
;; Not loaded by bb — requires JVM for Malli dependency.

(ns compile-standards
  (:require
   [clojure.pprint :as pprint]
   [clojure.walk :as walk]
   [ai.miniforge.policy-pack.mdc-compiler :as mdc-compiler]))

(def ^:private output-path
  "components/phase/resources/packs/miniforge-standards.pack.edn")

(defn- instant->date
  "Convert java.time.Instant to java.util.Date for #inst EDN serialization."
  [v]
  (if (instance? java.time.Instant v)
    (java.util.Date/from v)
    v))

(defn -main [& _args]
  (let [result (mdc-compiler/compile-standards-pack ".standards")]
    (if (:success? result)
      (let [pack (walk/postwalk instant->date (:pack result))]
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
