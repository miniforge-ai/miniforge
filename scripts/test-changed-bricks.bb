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

(defn poly-changed-names
  "Query poly for changed brick names since main. Returns a vector of strings."
  [key]
  (let [{:keys [out]} (p/sh {:out :string}
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

(defn build-test-expr
  "Build a Clojure expression that runs bricks in parallel, but namespaces
   within each brick sequentially.

   with-redefs (used heavily in phase/agent tests) mutates global var roots,
   so namespaces sharing the same with-redefs targets must not run concurrently.
   Brick boundaries are the natural isolation unit — different bricks don't
   share mocked vars."
  [brick-groups]
  (let [quote-ns (fn [n] (str "'" n))
        all-nses (mapcat val brick-groups)
        require-expr (str/join " " (map quote-ns all-nses))
        ;; Build a vector of vectors: [[ns1 ns2] [ns3] ...]
        groups-expr (str "["
                         (str/join " "
                           (map (fn [[_brick nses]]
                                  (str "[" (str/join " " (map quote-ns nses)) "]"))
                                brick-groups))
                         "]")]
    (str "(require 'clojure.test " require-expr ") "
         "(let [groups " groups-expr
         "      run-group (fn [nses] "
         "                  (reduce (fn [acc ns] "
         "                            (merge-with + acc "
         "                              (select-keys (clojure.test/run-tests ns) "
         "                                           [:test :pass :fail :error]))) "
         "                          {:test 0 :pass 0 :fail 0 :error 0} nses))"
         "      results (doall (pmap run-group groups))"
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
   phase-software-factory tests redefine agent/invoke, agent/create-implementer,
   etc., so they must run in the same sequential group as the agent brick."
  {;; phase-software-factory tests redefine agent/invoke etc.
   "agent+phases" #{"agent" "phase-software-factory"}
   ;; workflow and phase tests both dynamically require context_pack via
   ;; ensure-phase-implementations-loaded! — concurrent pmap loading causes
   ;; Compiler$CompilerException races on context_pack/interface.clj.
   "workflow+phase" #{"workflow" "phase"}})

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
        total-nses (reduce + 0 (map count (vals brick-groups)))]
    (if (empty? brick-groups)
      (println "✓ No changed bricks — nothing to test")
      (do
        (println (str "  Changed bricks: " (str/join ", " (keys brick-groups))))
        (println (str "  Test namespaces (" total-nses " across " (count brick-groups) " bricks):"))
        (doseq [[brick nses] brick-groups]
          (println (str "    " brick " (" (count nses) " namespaces)"))
          (doseq [ns nses] (println (str "      " ns))))
        (let [expr (build-test-expr brick-groups)
              {:keys [exit]} (deref (p/process {:out :inherit :err :inherit}
                                               "clojure" "-M:dev:test" "-e" expr))]
          (when-not (zero? exit)
            (println "❌ Tests failed with exit code:" exit)
            (System/exit exit)))))))

(-main)
