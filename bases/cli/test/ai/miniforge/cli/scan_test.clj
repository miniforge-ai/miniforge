;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0
(ns ai.miniforge.cli.scan-test
  "Tests for the scan CLI command — pack resolution, selector parsing, pipeline."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.compliance-scanner.interface :as compliance-scanner]
   [ai.miniforge.cli.main.commands.scan :as sut])
  (:import [java.io File]))

;------------------------------------------------------------------------------ Layer 0

;; Access private functions via var
(def ^{:stratum 0} resolve-rules-selector (var-get #'sut/resolve-rules-selector))

(def ^{:stratum 0} resolve-pack (var-get #'sut/resolve-pack))

(def ^{:stratum 0} build-scan-opts (var-get #'sut/build-scan-opts))

;------------------------------------------------------------------------------ Layer 1

;; ============================================================================
;; Rule selector parsing
;; ============================================================================
(deftest ^{:stratum 1} resolve-rules-selector-test
  (testing "nil defaults to :all"
    (is (= :all (resolve-rules-selector nil))))

  (testing "string 'all' returns :all"
    (is (= :all (resolve-rules-selector "all"))))

  (testing "string 'always-apply' returns :always-apply"
    (is (= :always-apply (resolve-rules-selector "always-apply"))))

  (testing "other strings become keywords"
    (is (= :std/clojure (resolve-rules-selector "std/clojure")))))

;; ============================================================================
;; Pack resolution
;; ============================================================================
(deftest ^{:stratum 1} resolve-pack-from-classpath-test
  (testing "loads reference pack by name from classpath"
    (let [pack (resolve-pack "foundations-1.0.0")]
      (is (some? pack))
      (when pack
        (is (= "miniforge/foundations" (:pack/id pack)))
        (is (seq (:pack/rules pack))))))

  (testing "returns nil for nonexistent pack"
    (is (nil? (resolve-pack "nonexistent-pack-9999")))))

(deftest ^{:stratum 1} resolve-pack-from-file-test
  (testing "loads pack from file path"
    (let [pack (resolve-pack "components/compliance-scanner/resources/test-fixtures/clojure-210-pack.edn")]
      (is (some? pack))
      (when pack
        (is (seq (:pack/rules pack)))))))

;; ============================================================================
;; Scan opts construction
;; ============================================================================
(deftest ^{:stratum 1} build-scan-opts-test
  (testing "defaults to :all rules"
    (let [opts (build-scan-opts {} nil)]
      (is (= :all (:rules opts)))))

  (testing "includes pack when provided"
    (let [opts (build-scan-opts {:pack "foundations-1.0.0"} nil)]
      (is (some? (:pack opts)))
      (is (= :all (:rules opts)))))

  (testing "includes since ref when provided"
    (let [opts (build-scan-opts {:since "HEAD~5"} nil)]
      (is (= "HEAD~5" (:since opts)))))

  (testing "passes rules selector through"
    (let [opts (build-scan-opts {:rules "always-apply"} nil)]
      (is (= :always-apply (:rules opts)))))

  (testing "config-pack used when no --pack flag and repo-config is present"
    ;; repo-config with :repo/packs → config-pack is resolved and used
    (let [opts (build-scan-opts {} {:repo/packs ["foundations-1.0.0"]})]
      (is (some? (:pack opts)))))

  (testing "malformed explicit pack returns canonical anomaly"
    (let [tmp  (File/createTempFile "bad-pack" ".edn")
          _    (do (.deleteOnExit tmp) (spit (.getPath tmp) "{:pack/rules [(:invalid"))
          opts (build-scan-opts {:pack (.getPath tmp)}
                                {:repo/packs ["foundations-1.0.0"]})]
      (is (anomaly/anomaly? opts)
          "malformed --pack returns a canonical anomaly")
      (is (= :anomalies.scan/edn-parse-error (anomaly/subtype opts))
          "subtype is :anomalies.scan/edn-parse-error")
      (is (string? (get-in opts [:anomaly/data :source])))))

  (testing "malformed repo config propagates anomaly through build-scan-opts"
    (let [fake-err (anomaly/sub-anomaly :invalid-input :anomalies.scan/edn-parse-error
                                       "EOF while reading"
                                       {:source "test/.miniforge/config.edn"})
          opts     (build-scan-opts {} fake-err)]
      (is (anomaly/anomaly? opts)
          "anomaly repo-config propagates through build-scan-opts"))))

;; ============================================================================
;; scan-cmd pipeline regression
;; ============================================================================
(deftest ^{:stratum 1} scan-cmd-stops-on-malformed-pack-test
  (testing "scanner/scan is never called and error printed exactly once when --pack is malformed"
    (let [tmp (File/createTempFile "bad-pack" ".edn")]
      (try
        (spit (.getPath tmp) "{:invalid")
        (let [called (atom false)
              output (with-out-str
                       (with-redefs [compliance-scanner/scan (fn [& _] (reset! called true) {})]
                         (sut/scan-cmd {:pack (.getPath tmp)})))]
          (is (false? @called)
              "scanner/scan must not be called when explicit --pack is malformed")
          (is (= 1 (count (re-seq #"Failed to parse EDN" output)))
              "error diagnostic must appear exactly once"))
        (finally
          (.delete tmp))))))

(deftest ^{:stratum 1} scan-cmd-stops-on-malformed-repo-config-test
  (testing "scanner/scan never called and error printed once when .miniforge/config.edn is malformed"
    (let [tmp-dir (doto (File/createTempFile "test-repo" nil) (.delete) (.mkdirs))]
      (try
        (let [cfg-dir (doto (File. tmp-dir ".miniforge") (.mkdirs))]
          (spit (File. cfg-dir "config.edn") "{:invalid-edn")
          (let [called (atom false)
                output (with-out-str
                         (with-redefs [compliance-scanner/scan (fn [& _] (reset! called true) {})]
                           (sut/scan-cmd {:repo (.getPath tmp-dir)})))]
            (is (false? @called)
                "scanner/scan must not be called when .miniforge/config.edn is malformed")
            (is (= 1 (count (re-seq #"Failed to parse EDN" output)))
                "error diagnostic must appear exactly once")))
        (finally
          (doseq [f (reverse (file-seq tmp-dir))] (.delete f)))))))

(deftest ^{:stratum 1} scan-cmd-stops-on-malformed-configured-pack-test
  (testing "scanner/scan never called and error printed once when a configured pack is malformed"
    (let [tmp-dir  (doto (File/createTempFile "test-repo" nil) (.delete) (.mkdirs))
          bad-pack (File/createTempFile "bad-pack" ".edn")]
      (try
        (let [cfg-dir (doto (File. tmp-dir ".miniforge") (.mkdirs))]
          (spit bad-pack "{:invalid")
          (spit (File. cfg-dir "config.edn")
                (str "{:repo/packs [" (pr-str (.getPath bad-pack)) "]}"))
          (let [called (atom false)
                output (with-out-str
                         (with-redefs [compliance-scanner/scan (fn [& _] (reset! called true) {})]
                           (sut/scan-cmd {:repo (.getPath tmp-dir)})))]
            (is (false? @called)
                "scanner/scan must not be called when a configured pack is malformed")
            (is (= 1 (count (re-seq #"Failed to parse EDN" output)))
                "error diagnostic must appear exactly once")))
        (finally
          (.delete bad-pack)
          (doseq [f (reverse (file-seq tmp-dir))] (.delete f)))))))

;; ============================================================================
;; Negative-mode violation messages
;; ============================================================================
(deftest ^{:stratum 1} negative-mode-uses-rule-title-test
  (testing "negative-mode violations use rule title, not hardcoded header message"
    (let [k8s    (resolve-pack "kubernetes-1.0.0")
          result (compliance-scanner/scan "." ".standards" {:rules :all :pack k8s})]
      (doseq [v (:violations result)]
        (is (not= "(missing copyright header)" (:current v))
            (str "Violation in " (:file v) " should not use hardcoded header message"))))))
