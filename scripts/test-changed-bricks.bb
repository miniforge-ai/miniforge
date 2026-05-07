#!/usr/bin/env bb
;; Brick-Parallel Test Runner for Changed Bricks
;; ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
;;
;; Queries Polylith for bricks changed since main, resolves their test
;; namespaces, and runs them with brick-level parallelism.
;;
;; Namespaces within a brick run sequentially (they share with-redefs targets),
;; while different bricks run in parallel (they don't share mutable state).
;;
;; Usage:
;;   bb scripts/test-changed-bricks.bb

(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[clojure.string :as str])

;; --------------------------------------------------------------------------- Poly integration

(def ^:private clean-git-env
  "Environment with GIT_INDEX_FILE stripped.
   Prevents worktree-specific index from leaking into subprocesses that
   run git commands (Polylith, Clojure test JVM). Without this, git
   operations inside subprocesses can corrupt the committing worktree's
   index, causing empty or wrong-tree commits."
  (dissoc (into {} (System/getenv)) "GIT_INDEX_FILE"))

(def ^:private test-env
  "Environment for the test JVM with git worktree vars stripped.
   Test namespaces shell out to git against temp repos and worktrees, so the
   caller's worktree-specific git vars must not leak into child JVMs."
  (dissoc (into {} (System/getenv))
          "GIT_INDEX_FILE"
          "GIT_DIR"
          "GIT_WORK_TREE"
          "GIT_COMMON_DIR"))

(defn poly-changed-names
  "Query poly for changed brick names since main. Returns a vector of strings."
  [key]
  (let [{:keys [out]} (p/sh {:out :string :env clean-git-env}
                             "poly" "ws" (str "get:changes:" key) "since:main")
        trimmed (str/trim out)]
    (if (or (str/blank? trimmed) (= trimmed "nil") (= trimmed "[]"))
      []
      (->> (re-seq #"\"([^\"]+)\"" trimmed)
           (mapv second)))))

;; --------------------------------------------------------------------------- Namespace discovery

(defn find-test-nses
  "Find test namespace names under a brick's test directory."
  [brick-type brick-name]
  (let [dir (str brick-type "/" brick-name "/test")]
    (when (fs/exists? dir)
      (->> (fs/glob dir "**/*_test.clj")
           (mapv (fn [path]
                   (-> (str path)
                       (subs (inc (count dir)))
                       (str/replace #"\.clj$" "")
                       (str/replace "/" ".")
                       (str/replace "_" "-"))))))))

;; --------------------------------------------------------------------------- Brick-parallel test execution

(def ^:private isolated-bricks-env-var
  "Environment variable that overrides the isolated brick set.
   Value should be a comma-separated list such as:
   MINIFORGE_TEST_ISOLATED_BRICKS=pipeline-pack-store,artifact"
  "MINIFORGE_TEST_ISOLATED_BRICKS")

(def ^:private default-isolated-bricks
  "Bricks that should run in their own test JVM.
   Some native-backed stores are reliable in isolation but can fail inside the
   large aggregate JVM used for changed-brick testing."
  #{"pipeline-pack-store"})

(defn configured-isolated-bricks
  "Return the configured isolated brick set.
   A comma-separated MINIFORGE_TEST_ISOLATED_BRICKS overrides the defaults."
  []
  (if-let [raw (some-> (System/getenv isolated-bricks-env-var) str/trim seq)]
    (->> (str/split raw #",")
         (map str/trim)
         (remove str/blank?)
         set)
    default-isolated-bricks))

(defn build-test-expr
  "Build a Clojure expression that runs brick groups in a single test JVM.
   Namespaces within a brick always run sequentially.

   with-redefs (used heavily in phase/agent tests) mutates global var roots,
   so namespaces sharing the same with-redefs targets must not run concurrently.
   Brick boundaries are the natural isolation unit — different bricks don't
   share mocked vars."
  [brick-groups parallel?]
  (let [quote-ns (fn [n] (str "'" n))
        all-nses (mapcat val brick-groups)
        require-expr (str/join " " (map quote-ns all-nses))
        groups-expr (str "["
                         (str/join " "
                                   (map (fn [[_brick nses]]
                                          (str "[" (str/join " " (map quote-ns nses)) "]"))
                                        brick-groups))
                         "]")
        results-expr (if parallel?
                       "(doall (pmap run-group groups))"
                       "(mapv run-group groups)")]
    (str "(require 'clojure.test " require-expr ") "
         "(let [groups " groups-expr
         "      run-group (fn [nses] "
         "                  (reduce (fn [acc ns] "
         "                            (merge-with + acc "
         "                              (select-keys (clojure.test/run-tests ns) "
         "                                           [:test :pass :fail :error]))) "
         "                          {:test 0 :pass 0 :fail 0 :error 0} nses))"
         "      results " results-expr
         "      summary (reduce (fn [acc r] (merge-with + acc r)) "
         "                      {:test 0 :pass 0 :fail 0 :error 0} results)]"
         "  (println) "
         "  (println (str \"Total: \" (:test summary) \" tests, \""
         "               (:pass summary) \" passes, \""
         "               (:fail summary) \" failures, \""
         "               (:error summary) \" errors\"))"
         "  (System/exit (if (zero? (+ (:fail summary) (:error summary))) 0 1)))")))

;; --------------------------------------------------------------------------- Affinity groups
;; Bricks whose tests with-redefs vars from other bricks must run in the
;; same sequential group.  Key = canonical name, value = set of bricks that
;; must be coalesced into one group when any of them is changed.

(def affinity-groups
  "Bricks that share with-redefs targets and must NOT run in parallel.
   workflow, phase, agent, and phase-software-factory all exercise the same
   phase pipeline and redefine shared globals such as agent/invoke,
   agent/create-implementer, verify/run-tests!, and
   release-executor/execute-release-phase. Running them in separate pmap
   groups causes intermittent cross-brick test pollution."
  {"workflow+agent+phases" #{"workflow" "phase" "agent" "phase-software-factory"}})

(defn coalesce-by-affinity
  "Merge brick-groups that belong to the same affinity group into a single
   sequential group. Returns an updated brick-groups map."
  [brick-groups]
  (let [brick-names (set (keys brick-groups))]
    (reduce
      (fn [groups [group-key members]]
        (let [present (filter brick-names members)]
          (if (<= (count present) 1)
            groups
            (let [merged-nses (vec (mapcat #(get groups %) present))
                  without (apply dissoc groups present)]
              (assoc without group-key merged-nses)))))
      brick-groups
      affinity-groups)))

(defn partition-isolated-bricks
  "Split brick-groups into {:isolated ... :parallel ...} by brick name."
  [brick-groups]
  (let [isolated-bricks (configured-isolated-bricks)]
    (reduce-kv
     (fn [acc brick nses]
       (update acc (if (isolated-bricks brick) :isolated :parallel) assoc brick nses))
     {:isolated {} :parallel {}}
     brick-groups)))

(defn run-test-jvm!
  "Run the given brick groups in a dedicated Clojure test JVM.
   Returns the process exit code."
  [brick-groups parallel?]
  (let [expr (build-test-expr brick-groups parallel?)
        {:keys [exit]} (deref (p/process {:out :inherit :err :inherit
                                          :env test-env}
                                         "clojure" "-M:dev:test" "-e" expr))]
    exit))


;; --------------------------------------------------------------------------- Main

(defn -main []
  (println "🧪 Detecting changed bricks since main...")
  (let [components (poly-changed-names "changed-components")
        bases (poly-changed-names "changed-bases")
        ;; Group namespaces by brick so we can parallelize across bricks
        ;; but run sequentially within each brick
        brick-groups (-> (into {}
                              (concat
                               (for [c components
                                     :let [nses (find-test-nses "components" c)]
                                     :when (seq nses)]
                                 [c nses])
                               (for [b bases
                                     :let [nses (find-test-nses "bases" b)]
                                     :when (seq nses)]
                                 [b nses])))
                         coalesce-by-affinity)
        {:keys [isolated parallel]} (partition-isolated-bricks brick-groups)
        total-nses (reduce + 0 (map count (vals brick-groups)))]
    (if (empty? brick-groups)
      (println "✓ No changed bricks — nothing to test")
      (do
        (println (str "  Changed bricks: " (str/join ", " (keys brick-groups))))
        (when (seq isolated)
          (println (str "  Isolated bricks: " (str/join ", " (keys isolated)))))
        (println (str "  Test namespaces (" total-nses " across " (count brick-groups) " bricks):"))
        (doseq [[brick nses] brick-groups]
          (println (str "    " brick " (" (count nses) " namespaces)"))
          (doseq [ns nses] (println (str "      " ns))))
        (doseq [[brick nses] isolated]
          (println (str "▶ Running isolated brick: " brick))
          (let [exit (run-test-jvm! {brick nses} false)]
            (when-not (zero? exit)
              (println "❌ Tests failed with exit code:" exit)
              (System/exit exit))))
        (when (seq parallel)
          (let [exit (run-test-jvm! parallel true)]
            (when-not (zero? exit)
              (println "❌ Tests failed with exit code:" exit)
              (System/exit exit))))))))

(-main)
