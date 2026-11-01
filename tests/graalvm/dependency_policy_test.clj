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
   [:aliases :test :replace-deps] [:tasks :verify :extra-deps]])

(defn- ^{:stratum 0} manifest-at [path coordinate]
  (pr-str (assoc-in {} path {'org.clojure/data.json coordinate})))

(defn- ^{:stratum 0} manifests []
  (concat [(io/file "deps.edn") (io/file "bb.edn")]
        (for [root ["components" "bases" "projects"]
              file (file-seq (io/file root))
              :when (#{"deps.edn" "bb.edn"} (.getName ^java.io.File file))]
          file)))

(deftest ^{:stratum 0} classifier-artifacts-are-not-the-verified-artifact
  (doseq [library '[org.clojure/data.json$sources org.clojure/data.json$tests]
          version ["2.4.0" "2.5.2"]]
    (let [text (pr-str {:deps {library {:mvn/version version}}})]
      (is (false? (policy/compatible-json? text))))))

(deftest ^{:stratum 0} task-reader-forms-remain-data-not-executable-reader-input
  (is (policy/compatible-json?
       "{:deps {org.clojure/data.json {:mvn/version \"2.4.0\"}} :tasks {verify {:task #(identity %)}}}"))
  (is (thrown? RuntimeException (policy/compatible-json? "#=(identity {:deps {}})"))))

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
  (is (some #{(io/file "bb.edn")} (manifests)) "The native task manifest must be scanned")
  (doseq [file (manifests)]
    (is (policy/compatible-json? (slurp file))
        (str file " selects an unverified JSON coordinate"))))

(comment ::native-json-policy)
