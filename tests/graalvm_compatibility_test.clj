(ns graalvm-compatibility-test
  "GraalVM/Babashka compatibility test suite.

   Ensures ALL miniforge bricks (components + bases) can be loaded in
   Babashka/GraalVM native image environments. New bricks are discovered
   automatically from the filesystem — no manual list to maintain.

   Run with:
     bb test:graalvm

   Or add to CI/pre-commit (already wired in bb.edn)."
  (:require
   [clojure.test :refer [deftest is testing run-tests]]
   [clojure.string :as str]
   [clojure.java.io :as io]))

;; ============================================================================
;; Discovery
;; ============================================================================

(defn- discover-interface-namespaces
  "Scan components/ for interface.clj files and derive namespace symbols.
   Returns a sorted seq of namespace symbols."
  []
  (let [component-dirs (-> (io/file "components") .listFiles seq)
        interface-files (->> component-dirs
                             (filter #(.isDirectory %))
                             (mapcat (fn [dir]
                                       (let [;; components/<name>/src/ai/miniforge/<name>/interface.clj
                                             ;; Name may contain hyphens -> underscores in path
                                             brick-name (.getName dir)
                                             path-name (str/replace brick-name "-" "_")
                                             iface (io/file dir "src" "ai" "miniforge" path-name "interface.clj")]
                                         (when (.exists iface)
                                           [(symbol (str "ai.miniforge." brick-name ".interface"))])))))]
    (sort-by str interface-files)))

(defn- path->namespace
  "Convert a .clj file path (relative to src/) to a namespace symbol.
   e.g. ai/miniforge/cli/main.clj -> ai.miniforge.cli.main"
  [src-root clj-file]
  (let [src-path (.getPath src-root)
        file-path (.getPath clj-file)
        relative (subs file-path (inc (count src-path)))]
    (-> relative
        (str/replace #"\.clj$" "")
        (str/replace "/" ".")
        (str/replace "_" "-")
        symbol)))

(defn- discover-base-namespaces
  "Scan bases/ for all .clj source files and derive namespace symbols.
   Returns a sorted seq of namespace symbols."
  []
  (let [base-dirs (-> (io/file "bases") .listFiles seq)]
    (->> base-dirs
         (filter #(.isDirectory %))
         (mapcat (fn [dir]
                   (let [src-dir (io/file dir "src")]
                     (when (.exists src-dir)
                       (->> (file-seq src-dir)
                            (filter #(str/ends-with? (.getName %) ".clj"))
                            (map #(path->namespace src-dir %)))))))
         (sort-by str))))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- require-namespace
  "Try to require a namespace. Returns [success? error-message]."
  [ns-symbol]
  (try
    (require ns-symbol)
    [true nil]
    (catch Exception e
      [false (str "Failed to require " ns-symbol ": " (ex-message e))])))

(defn- classpath-error?
  "Check if the error is a classpath/file-not-found issue (not a compatibility issue)."
  [error-msg]
  (some #(str/includes? (or error-msg "") %)
        ["Could not locate"
         "FileNotFoundException"
         "on classpath"]))

(defn- jvm-only-error?
  "Check if the error indicates JVM-only features that block GraalVM/Babashka."
  [error-msg]
  (some #(str/includes? (or error-msg "") %)
        ["definterface"
         "gen-class"
         "proxy"
         "reify with Java interfaces"
         "defrecord/deftype"
         "java.io.Closeable"
         "No matching clause"]))

;; ============================================================================
;; Tests
;; ============================================================================

(deftest test-all-components-load-in-babashka
  (testing "All component interfaces should load in Babashka/GraalVM"
    (let [all-ns (discover-interface-namespaces)]
      ;; Ensure we actually found bricks (sanity check)
      (is (>= (count all-ns) 20)
          (str "Expected 20+ component interfaces, found " (count all-ns)
               ". Is the test running from the repo root?"))

      (doseq [ns-sym all-ns]
        (let [[success? error-msg] (require-namespace ns-sym)]
          (cond
            ;; Loaded fine
            success?
            (is true (str ns-sym " loaded"))

            ;; Not on classpath — warn but don't fail
            ;; (brick exists but not wired into workspace :dev alias yet)
            (classpath-error? error-msg)
            (println "  WARN:" ns-sym "- not on :dev classpath (add to deps.edn :dev alias)")

            ;; JVM-only code detected — hard failure
            (jvm-only-error? error-msg)
            (is false (str "JVM-ONLY DEPENDENCY DETECTED in " ns-sym ":\n"
                           error-msg
                           "\n\nThis blocks GraalVM native compilation."
                           "\nFix: use Babashka-compatible alternatives."))

            ;; Other error — fail with details
            :else
            (is false (str "Failed to load " ns-sym ":\n" error-msg))))))))

(deftest test-all-bases-load-in-babashka
  (testing "All base namespaces should load in Babashka/GraalVM"
    (let [all-ns (discover-base-namespaces)]
      ;; Ensure we actually found base namespaces (sanity check)
      (is (>= (count all-ns) 3)
          (str "Expected 3+ base namespaces, found " (count all-ns)
               ". Is the test running from the repo root?"))

      (doseq [ns-sym all-ns]
        (let [[success? error-msg] (require-namespace ns-sym)]
          (cond
            success?
            (is true (str ns-sym " loaded"))

            (classpath-error? error-msg)
            (println "  WARN:" ns-sym "- not on :dev classpath (add to deps.edn :dev alias)")

            (jvm-only-error? error-msg)
            (is false (str "JVM-ONLY DEPENDENCY DETECTED in " ns-sym ":\n"
                           error-msg
                           "\n\nThis blocks GraalVM native compilation."
                           "\nFix: use Babashka-compatible alternatives."))

            :else
            (is false (str "Failed to load " ns-sym ":\n" error-msg))))))))

(deftest test-no-forbidden-dependencies
  (testing "Forbidden JVM-only dependencies should not be present"
    (let [forbidden-deps {"org.clojure/data.json" "Use cheshire.core instead (bundled in Babashka)"
                          "org.clojure/java.jdbc" "Use next.jdbc instead"}
          all-dirs (concat (file-seq (io/file "components"))
                           (file-seq (io/file "bases")))
          deps-files (->> all-dirs
                          (filter #(= "deps.edn" (.getName %)))
                          (map slurp))]
      (doseq [[dep replacement] forbidden-deps
              deps-content deps-files]
        (is (not (str/includes? deps-content dep))
            (str "Found forbidden dependency: " dep "\n"
                 "Reason: Not GraalVM-compatible\n"
                 "Fix: " replacement))))))

(deftest test-no-java-interface-in-defrecord
  (testing "No defrecord should implement Java interfaces (Babashka limitation)"
    (let [src-files (->> (concat (file-seq (io/file "components"))
                                 (file-seq (io/file "bases")))
                         (filter #(str/ends-with? (.getName %) ".clj"))
                         (filter #(str/includes? (.getPath %) "/src/")))]
      (doseq [f src-files]
        (let [content (slurp f)
              rel-path (str/replace (.getPath f) (str (System/getProperty "user.dir") "/") "")]
          ;; Check for defrecord implementing java.io.Closeable or java.lang.AutoCloseable
          (when (and (str/includes? content "defrecord")
                     (or (str/includes? content "java.io.Closeable")
                         (str/includes? content "java.lang.AutoCloseable")))
            (is false (str rel-path " contains defrecord implementing Java interface.\n"
                           "Babashka's defrecord only supports Clojure protocols.\n"
                           "Fix: use a closure or protocol instead."))))))))

(deftest test-workflow-execution-available
  (testing "Workflow execution should be available in Babashka build"
    (let [[success? error-msg] (require-namespace 'ai.miniforge.workflow.interface)]
      (is success? (str "Workflow interface should load: " error-msg))
      (when success?
        (is (some? (resolve 'ai.miniforge.workflow.interface/load-workflow))
            "Should resolve load-workflow")
        (is (some? (resolve 'ai.miniforge.workflow.interface/run-pipeline))
            "Should resolve run-pipeline")))))

;; ============================================================================
;; Entry point
;; ============================================================================

(defn -main
  "Entry point for running tests from command line."
  []
  (let [result (run-tests 'graalvm-compatibility-test)]
    (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1))))

(comment
  ;; Run tests in REPL
  (run-tests 'graalvm-compatibility-test)

  ;; Discover all brick interfaces
  (discover-interface-namespaces)

  ;; Discover all base namespaces
  (discover-base-namespaces)

  ;; Test a specific namespace
  (require-namespace 'ai.miniforge.dag-executor.interface)

  :end)
