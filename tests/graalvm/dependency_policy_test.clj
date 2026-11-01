;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns graalvm.dependency-policy-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [graalvm.dependency-policy :as policy]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private verified-coordinate {:mvn/version "2.4.0"})

(def ^{:stratum 0} ^:private dependency-sites
  [[:deps] [:override-deps]
   [:aliases :test :extra-deps] [:aliases :test :override-deps]
   [:aliases :test :replace-deps]])

(defn- ^{:stratum 0} manifest-at [path coordinate]
  (pr-str (assoc-in {} path {'org.clojure/data.json coordinate})))

(defn- ^{:stratum 0} manifests []
  (cons (io/file "deps.edn")
        (for [root ["components" "bases" "projects"]
              file (file-seq (io/file root))
              :when (= "deps.edn" (.getName ^java.io.File file))]
          file)))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} only-the-verified-json-coordinate-is-admitted
  (is (policy/compatible-json? "{:deps {}} ; org.clojure/data.json is absent"))
  (is (policy/compatible-json? "{:deps {org.clojure/data.json {:mvn/version \"2.4.0\"}}}"))
  (is (false? (policy/compatible-json? "{:deps {org.clojure/data.json {:mvn/version \"2.5.2\"}}}")))
  (is (policy/compatible-json? (manifest-at [:deps] verified-coordinate)))
  (doseq [coordinate [nil {} {:mvn/version "2.4.1"} {:mvn/version "2.5.0"}
                      {:mvn/version "2.5.2"} {:mvn/version "RELEASE"}
                      {:git/url "https://example.invalid/json" :git/sha "abc"}
                      {:local/root "../json"}
                      {:mvn/version "2.4.0" :classifier "unverified"}]]
    (is (false? (policy/compatible-json? (manifest-at [:deps] coordinate))))))

(deftest ^{:stratum 1} nested-dependency-sites-cannot-override-the-approved-release
  (doseq [path dependency-sites]
    (is (policy/compatible-json? (manifest-at path verified-coordinate)))
    (is (false? (policy/compatible-json? (manifest-at path {:mvn/version "2.5.2"})))))
  (let [manifest {:deps {'org.clojure/data.json verified-coordinate}
                  :aliases {:dev {:override-deps {'org.clojure/data.json {:mvn/version "2.5.2"}}}}}]
    (is (false? (policy/compatible-json? (pr-str manifest))))))

(deftest ^{:stratum 1} repository-manifests-preserve-native-json-admission
  (is (.isFile (io/file "deps.edn")) "Run this repository gate from its root")
  (doseq [file (manifests)]
    (is (policy/compatible-json? (slurp file))
        (str file " selects an unverified JSON coordinate"))))

(comment ::native-json-policy)
