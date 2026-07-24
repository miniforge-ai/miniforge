;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns graalvm-compatibility-test
  "GraalVM/Babashka compatibility test suite.

   Ensures every miniforge brick (component + base) that is *intended*
   to run under Babashka can actually be loaded there. Bricks that are
   known JVM-only opt out of the load attempt by declaring
   `{:miniforge/runtime :jvm-only}` in their namespace metadata; the
   discovery below reads that marker from the file text (without
   loading the namespace, which would otherwise pull in the BB-hostile
   transitive deps we're trying to avoid).

   Also enforces that no JVM-only brick ever ships inside
   `projects/miniforge/deps.edn`, because the miniforge CLI runs under
   Babashka.

   Run with:
     bb test:graalvm

   Or add to CI/pre-commit (already wired in bb.edn)."
  (:require
   [clojure.test :refer [deftest is testing run-tests]]
   [clojure.string :as str]
   [clojure.edn :as edn]
   [clojure.java.io :as io]))

;; ============================================================================
;; Runtime classification (reads ns-meta without loading the namespace)
;; ============================================================================

(def ^:private jvm-only-meta-re
  "Distinctive enough that the only legitimate place it can appear is a
   brick's ns-meta marker."
  #":miniforge/runtime\s+:jvm-only")

(defn- file-marked-jvm-only?
  [^java.io.File f]
  (and (.exists f)
       (boolean (re-find jvm-only-meta-re (slurp f)))))

(defn- component-interface-file
  [brick-name]
  (io/file "components" brick-name "src" "ai" "miniforge"
           (str/replace brick-name "-" "_") "interface.clj"))

(defn jvm-only-component?
  "True when `components/<brick-name>/…/interface.clj` self-declares
   `{:miniforge/runtime :jvm-only}` in its ns metadata."
  [brick-name]
  (file-marked-jvm-only? (component-interface-file brick-name)))

(defn- base-src-files
  [base-name]
  (let [src-dir (io/file "bases" base-name "src")]
    (when (.exists src-dir)
      (->> (file-seq src-dir)
           (filter #(str/ends-with? (.getName %) ".clj"))))))

(defn jvm-only-base?
  "True when any .clj file under `bases/<base-name>/src` carries the
   JVM-only marker — bases don't follow the single-interface convention
   so we treat any marked file as opting the whole base out."
  [base-name]
  (boolean (some file-marked-jvm-only? (base-src-files base-name))))

;; ============================================================================
;; Discovery
;; ============================================================================

(defn discover-interface-namespaces
  "Return one entry per component with an `interface.clj`:
     {:brick-name …, :ns-sym …, :jvm-only? bool}"
  []
  (->> (-> (io/file "components") .listFiles seq)
       (filter #(.isDirectory %))
       (keep (fn [dir]
               (let [brick-name (.getName dir)]
                 (when (.exists (component-interface-file brick-name))
                   {:brick-name brick-name
                    :ns-sym     (symbol (str "ai.miniforge." brick-name ".interface"))
                    :jvm-only?  (jvm-only-component? brick-name)}))))
       (sort-by :brick-name)))

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

(defn discover-base-namespaces
  "Return one entry per .clj file under `bases/<base>/src/`:
     {:ns-sym …, :file …, :jvm-only? bool}

   Per-file rather than per-base because some bases mix BB-safe and
   JVM-only source files, and the loader tests require each file
   independently."
  []
  (->> (-> (io/file "bases") .listFiles seq)
       (filter #(.isDirectory %))
       (mapcat (fn [dir]
                 (let [src-dir (io/file dir "src")]
                   (when (.exists src-dir)
                     (->> (file-seq src-dir)
                          (filter #(str/ends-with? (.getName %) ".clj"))
                          (map (fn [f]
                                 {:ns-sym    (path->namespace src-dir f)
                                  :file      f
                                  :jvm-only? (file-marked-jvm-only? f)})))))))
       (sort-by (comp str :ns-sym))))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn require-namespace
  "Try to require a namespace. Returns [success? error-message]."
  [ns-symbol]
  (try
    (require ns-symbol)
    [true nil]
    (catch Exception e
      [false (str "Failed to require " ns-symbol ": " (ex-message e))])))

(defn classpath-error?
  "Check if the error is a classpath/file-not-found issue (not a compatibility issue)."
  [error-msg]
  (some #(str/includes? (or error-msg "") %)
        ["Could not locate"
         "FileNotFoundException"
         "on classpath"]))

(defn jvm-only-error?
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
  (testing "Every BB-runtime component interface loads; JVM-only bricks are skipped."
    (let [bricks (discover-interface-namespaces)]
      (is (>= (count bricks) 20)
          (str "Expected 20+ component interfaces, found " (count bricks)
               ". Is the test running from the repo root?"))

      (doseq [{:keys [ns-sym jvm-only?]} bricks]
        (if jvm-only?
          (println "  SKIP:" ns-sym "- :miniforge/runtime :jvm-only")
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
                             "\nFix: either use Babashka-compatible alternatives, or add"
                             "\n     `{:miniforge/runtime :jvm-only}` to the ns metadata"
                             "\n     if the brick is deliberately JVM-only."))

              :else
              (is false (str "Failed to load " ns-sym ":\n" error-msg)))))))))

(deftest test-all-bases-load-in-babashka
  (testing "Every BB-runtime base namespace loads; JVM-only files are skipped."
    (let [files (discover-base-namespaces)]
      (is (>= (count files) 3)
          (str "Expected 3+ base namespaces, found " (count files)
               ". Is the test running from the repo root?"))

      (doseq [{:keys [ns-sym jvm-only?]} files]
        (if jvm-only?
          (println "  SKIP:" ns-sym "- :miniforge/runtime :jvm-only")
          (let [[success? error-msg] (require-namespace ns-sym)]
            (cond
              success?
              (is true (str ns-sym " loaded"))

              (classpath-error? error-msg)
              (println "  WARN:" ns-sym "- not on :dev classpath (add to deps.edn :dev alias)")

              (jvm-only-error? error-msg)
              (is false (str "JVM-ONLY DEPENDENCY DETECTED in " ns-sym ":\n"
                             error-msg
                             "\n\nFix: use Babashka-compatible alternatives, or mark the"
                             "\n     file with `{:miniforge/runtime :jvm-only}` ns-meta."))

              :else
              (is false (str "Failed to load " ns-sym ":\n" error-msg)))))))))

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
;; Execution gate — loading is not enough
;; ============================================================================
;;
;; Every test above proves a namespace LOADS under Babashka. That is a
;; weaker guarantee than it looks: SCI also refuses individual *method
;; calls* at invocation time, so a namespace can load cleanly and still
;; die on its first real call. The operator-event consumer did exactly
;; that — it held its cross-process guard with `(with-open [lock ...])`,
;; and `FileLockImpl.close` is not on SCI's allowlist. Because the
;; consumer runs inside the bb-hosted workflow runner behind a poller
;; that contains per-tick failures, the whole governed control path
;; degraded to a once-a-second warning that consumed nothing.
;;
;; So: exercise the call path, not just the require.

(defn- consumer-lock-acquirable?
  "Open a FRESH channel on the operator dir's `.consumer.lock` and try to
   acquire the lock, releasing by CLOSING THE CHANNEL (never the lock —
   bb forbids every `sun.nio.ch.FileLockImpl` method). Returns true iff
   `.tryLock` yields a lock.

   This is the direct check the twice-run pass cannot make on its own: an
   empty operator dir returns `{:routed 0 …}` whether the pass acquired
   the lock or bailed because it could not, so a leaked/still-held lock is
   invisible in the result map. Here a lock the consumer failed to release
   surfaces as either nil (another holder) or an
   `OverlappingFileLockException` (same JVM, overlapping region) — both
   map to false."
  [operator-dir]
  (let [lock-file (io/file operator-dir ".consumer.lock")
        channel (java.nio.channels.FileChannel/open
                 (.toPath lock-file)
                 (into-array java.nio.file.OpenOption
                             [java.nio.file.StandardOpenOption/CREATE
                              java.nio.file.StandardOpenOption/WRITE]))]
    (try
      (some? (.tryLock channel))
      (catch Exception _ false)
      (finally (.close channel)))))

(deftest test-operator-consumer-pass-executes-in-babashka
  (testing "consume-operator-events! runs a full pass under Babashka"
    (let [[loaded? error-msg] (require-namespace 'ai.miniforge.operator.interface)]
      (is loaded? (str "operator interface should load: " error-msg))
      (when loaded?
        (let [[es-loaded? es-err] (require-namespace 'ai.miniforge.event-stream.interface)]
          (is es-loaded? (str "event-stream interface should load: " es-err))
          (when es-loaded?
            (let [events-dir (io/file (System/getProperty "java.io.tmpdir")
                                      (str "bb-consumer-gate-" (System/currentTimeMillis)))
                  create-stream (resolve 'ai.miniforge.event-stream.interface/create-event-stream)
                  consume! (resolve 'ai.miniforge.operator.interface/consume-operator-events!)]
              ;; Fail with the missing symbol named, not an opaque NPE
              ;; when a nil resolve is invoked below — this gate exists to
              ;; catch these vars moving, so say WHICH one moved.
              (is (some? create-stream)
                  "event-stream/create-event-stream must resolve")
              (is (some? consume!)
                  "operator/consume-operator-events! must resolve")
              (when (and create-stream consume!)
               (let [stream (create-stream {:sinks []})]
                (.mkdirs (io/file events-dir "operator"))
              ;; An empty operator dir is enough: the pass still opens the
              ;; lock file, acquires the lock, and releases it — the exact
              ;; sequence that used to throw.
              (let [result (try
                             (consume! {:events-dir events-dir :stream stream})
                             (catch Exception e
                               {::threw (ex-message e)}))]
                (is (nil? (::threw result))
                    (str "consumer pass must not throw under Babashka: "
                         (::threw result)))
                (is (map? result) "a completed pass returns a result map")
                ;; Directly prove the pass RELEASED its lock: a fresh
                ;; acquirer must succeed. An empty-dir result map cannot
                ;; distinguish a released lock from a leaked one, so this
                ;; is the assertion that actually guards release.
                (is (consumer-lock-acquirable? (io/file events-dir "operator"))
                    "consumer must release its lock so a fresh acquirer succeeds")
                ;; Second pass is the additional regression check: the
                ;; release path runs again and still does not throw.
                (let [again (try
                              (consume! {:events-dir events-dir :stream stream})
                              (catch Exception e
                                {::threw (ex-message e)}))]
                  (is (nil? (::threw again))
                      (str "second pass must not throw (lock released?): "
                           (::threw again)))
                  (is (consumer-lock-acquirable? (io/file events-dir "operator"))
                      "second pass must also release its lock"))))))))))))

;; ============================================================================
;; miniforge-project BB-safety gate — no JVM-only bricks on the CLI classpath
;; ============================================================================

(defn- parse-deps-edn
  [^java.io.File f]
  (when (.exists f)
    (edn/read-string (slurp f))))

(defn- project-local-root-deps
  "Return every `:local/root` dep declared in `projects/<project>/deps.edn`,
   flattened across `:deps` and test `:extra-deps`. Each entry is
   `{:dep-sym … :local-root …}`."
  [project-name]
  (let [deps-edn (parse-deps-edn (io/file "projects" project-name "deps.edn"))]
    (for [[dep-sym dep-spec] (merge (:deps deps-edn)
                                    (get-in deps-edn [:aliases :test :extra-deps]))
          :let [local-root (:local/root dep-spec)]
          :when local-root]
      {:dep-sym    dep-sym
       :local-root local-root})))

(defn- classify-local-root
  "Map a `local-root` like `../../components/connector-http` or
   `../../bases/etl` to `{:kind :component|:base :name <brick-name>}`.
   Unrecognised shapes get `:kind nil`."
  [local-root]
  (let [parts (vec (str/split local-root #"/"))]
    (cond
      (some #{"components"} parts)
      {:kind :component
       :name (get parts (inc (.indexOf parts "components")))}

      (some #{"bases"} parts)
      {:kind :base
       :name (get parts (inc (.indexOf parts "bases")))}

      :else
      {:kind nil :name nil})))

(defn- jvm-only-local-root?
  [local-root]
  (let [{:keys [kind name]} (classify-local-root local-root)]
    (case kind
      :component (jvm-only-component? name)
      :base      (jvm-only-base? name)
      false)))

(deftest test-no-jvm-only-brick-in-miniforge-project
  (testing "projects/miniforge/deps.edn must not ship JVM-only bricks"
    (let [deps (project-local-root-deps "miniforge")]
      (is (seq deps)
          "Expected local-root brick deps in projects/miniforge/deps.edn")
      (doseq [{:keys [dep-sym local-root]} deps]
        (is (not (jvm-only-local-root? local-root))
            (str "JVM-only brick shipped in projects/miniforge/deps.edn: "
                 dep-sym " (" local-root ")"
                 "\n  This brick is marked {:miniforge/runtime :jvm-only}."
                 "\n  The miniforge CLI runs under Babashka and cannot load hato/POI/etc."
                 "\n  Fix: remove the dep from projects/miniforge/deps.edn, or route"
                 "\n       the feature through a JVM shell-out (see bases/etl for the"
                 "\n       canonical pattern)."))))))

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

  ;; Check a specific brick's runtime
  (jvm-only-component? "connector-http")
  (jvm-only-component? "connector-file")

  :end)
