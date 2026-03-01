#!/usr/bin/env bb
;; Parallel Test Runner for Changed Bricks
;; ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
;;
;; Queries Polylith for bricks changed since main, resolves their test
;; namespaces, and runs them in parallel via pmap in a single JVM.
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

;; --------------------------------------------------------------------------- Parallel test execution

(defn build-test-expr
  "Build a Clojure expression string that requires and runs test namespaces
   in parallel via pmap."
  [test-nses]
  (let [quote-ns (fn [n] (str "'" n))
        require-expr (str/join " " (map quote-ns test-nses))
        nses-expr (str/join " " (map quote-ns test-nses))]
    (str "(require 'clojure.test " require-expr ") "
         "(let [nses [" nses-expr "]"
         "      results (doall (pmap (fn [ns] "
         "                             (clojure.test/run-tests ns)) "
         "                           nses))"
         "      summary (reduce (fn [acc r] "
         "                        (merge-with + acc (select-keys r [:test :pass :fail :error]))) "
         "                      {:test 0 :pass 0 :fail 0 :error 0} results)]"
         "  (println) "
         "  (println (str \"Total: \" (:test summary) \" tests, \""
         "               (:pass summary) \" passes, \""
         "               (:fail summary) \" failures, \""
         "               (:error summary) \" errors\"))"
         "  (System/exit (if (zero? (+ (:fail summary) (:error summary))) 0 1)))")))

;; --------------------------------------------------------------------------- Main

(defn -main []
  (println "🧪 Detecting changed bricks since main...")
  (let [components (poly-changed-names "changed-components")
        bases (poly-changed-names "changed-bases")
        test-nses (vec (concat
                         (mapcat #(find-test-nses "components" %) components)
                         (mapcat #(find-test-nses "bases" %) bases)))]
    (if (empty? test-nses)
      (println "✓ No changed bricks — nothing to test")
      (do
        (println (str "  Changed: " (str/join ", " (concat components bases))))
        (println (str "  Test namespaces (" (count test-nses) "):"))
        (doseq [ns test-nses] (println (str "    " ns)))
        (let [expr (build-test-expr test-nses)
              {:keys [exit]} (deref (p/process {:out :inherit :err :inherit}
                                               "clojure" "-M:dev:test" "-e" expr))]
          (when-not (zero? exit)
            (println "❌ Tests failed with exit code:" exit)
            (System/exit exit)))))))

(-main)
