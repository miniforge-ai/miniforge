;; Analytic cost model for the policy-judge strategies. NOT a live measurement —
;; the CLI backend exposes no cache_control and reports zero token usage, so the
;; caching win is computed from the prompt's token structure + published cache
;; rates. Input-token cost only (output is small and separate). The cached model
;; assumes a cache-optimal prompt order: A = system+file cached / rule varies;
;; B = system+rules cached / file varies (both require a one-time prompt reorder).
;; Run: clojure -M:dev:test -i eval/policy-fidelity/cost_model.clj

(require '[ai.miniforge.phase.agent-behavior :as ab]
         '[ai.miniforge.semantic-analyzer.interface :as sem]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(defn toks [s] (quot (+ (count (or s "")) 3) 4))

;; per-1M-token USD input rate (write = 1.25x, read = 0.1x folded into token math)
(def input-price {"opus-4.8" 5.0 "sonnet-4.6" 3.0})
(defn cost [model toks-in] (* (/ toks-in 1.0e6) (input-price model)))

(def batched-system
  (str "You are a code reviewer enforcing engineering standards. Analyze ONE source "
       "file against a NUMBERED LIST of rules. Return ONLY a valid EDN vector of "
       "violation maps. Each map: {:rule-id <keyword> :line <int> :current <snippet> "
       ":message <why>}. Return [] if the file complies with all rules."))
(defn batched-rules-block [rules]
  (str "## Rules\n\n"
       (str/join "\n" (map (fn [r] (str "### " (:rule/id r) " — " (:rule/title r) "\n"
                                        (:rule/description r) "\n" (:rule/knowledge-content r))) rules))))

(def fs (java.nio.file.FileSystems/getDefault))
(defn glob? [g p] (try (.matches (.getPathMatcher fs (str "glob:" g)) (.getPath (io/file p))) (catch Exception _ false)))
(defn applies? [rule phase path]
  (let [phs (get-in rule [:rule/applies-to :phases])
        globs (get-in rule [:rule/applies-to :file-globs])]
    (and (or (nil? phs) (empty? phs) (contains? (set phs) phase))
         (or (nil? globs) (empty? globs) (some #(glob? % path) globs)))))

(let [all   (vec (into (ab/load-builtin-rules) (ab/load-standards-rules)))
      files ["components/agent/src/ai/miniforge/agent/context_budget.clj"
             "components/agent/src/ai/miniforge/agent/reviewer.clj"
             "components/agent/src/ai/miniforge/agent/reviewer/prompts.clj"
             "components/agent/src/ai/miniforge/agent/reviewer/artifact.clj"
             "components/agent/src/ai/miniforge/agent/planner.clj"]
      f0    (first files)
      rules (filterv #(applies? % :review f0) all)
      nf    (count files)
      nr    (count rules)
      ;; component token sizes
      sysA  (toks (:system (sem/build-judge-prompt (first rules) f0 "")))
      ruleA (into {} (for [r rules] [(:rule/id r) (toks (:user (sem/build-judge-prompt r f0 "")))]))
      sysB  (toks batched-system)
      rulesB (toks (batched-rules-block rules))
      filet (into {} (for [f files] [f (toks (slurp (io/file f)))]))
      sum-files (reduce + (vals filet))
      sum-rulesA (reduce + (vals ruleA))
      ;; A: per (file,rule) call. uncached = sysA + ruleA[r] + file[f] each.
      a-calls (* nf nr)
      a-unc   (reduce + (for [f files r rules] (+ sysA (ruleA (:rule/id r)) (filet f))))
      ;; cached (reorder system+file before rule): system cached globally, file cached per-file-group
      a-cac   (+ (* sysA (+ 1.25 (* 0.1 (dec a-calls))))                 ; system: 1 write + rest reads
                 (reduce + (for [f files] (* (filet f) (+ 1.25 (* 0.1 (dec nr)))))) ; file: per group 1 write + (nr-1) reads
                 (* nf sum-rulesA))                                       ; rule suffix: full, each rule in nf files
      ;; B: per file call. uncached = sysB + rulesB + file[f].
      b-calls nf
      b-unc   (reduce + (for [f files] (+ sysB rulesB (filet f))))
      b-cac   (+ (* (+ sysB rulesB) (+ 1.25 (* 0.1 (dec nf))))           ; system+rules: 1 write + (nf-1) reads
                 sum-files)                                             ; file suffix: full
      ;; B cross-run warm: the compiled rule-pack prefix is identical across
      ;; runs/repos, so within the cache TTL every call READS it (0.1x) — the
      ;; 1.25x write is paid once per TTL window and amortizes toward zero.
      ;; Only the changed files (the novel content) remain full price.
      b-xrun  (+ (* (+ sysB rulesB) nf 0.1)                             ; rule-pack: all reads
                 sum-files)                                             ; file suffix: full
      b-warmup (* (+ sysB rulesB) 1.25)]                                ; one-time write, amortized
  (println (format "Fixture: %d files, %d review-applicable rules\n" nf nr))
    (println (format "%-22s %8s %12s %12s" "config" "calls" "input-tok" "USD/gate"))
    (doseq [[label calls tk model]
            [["sonnet-A uncached" a-calls a-unc "sonnet-4.6"]
             ["sonnet-A cached"   a-calls a-cac "sonnet-4.6"]
             ["opus-A   uncached" a-calls a-unc "opus-4.8"]
             ["opus-B   uncached" b-calls b-unc "opus-4.8"]
             ["opus-B   cached/gate" b-calls b-cac "opus-4.8"]
             ["opus-B   cross-run" b-calls b-xrun "opus-4.8"]
             ["sonnet-B uncached" b-calls b-unc "sonnet-4.6"]]]
      (println (format "%-22s %8d %12d %12.4f"
                       label calls (long tk) (cost model tk))))
    (println (format "\nopus-B one-time rule-pack write (amortized over a TTL window): $%.4f" (cost "opus-4.8" b-warmup)))
    (println (format "\nuncached: opus-B is %.2fx the cost of sonnet-A" (/ (cost "opus-4.8" b-unc) (cost "sonnet-4.6" a-unc))))
    (println (format "cached:   opus-B is %.2fx the cost of sonnet-A" (/ (cost "opus-4.8" b-cac) (cost "sonnet-4.6" a-cac))))
    (println (format "caching cuts opus-B input cost %.0f%%" (* 100.0 (- 1.0 (/ b-cac b-unc)))))
    (println (format "caching cuts sonnet-A input cost %.0f%%" (* 100.0 (- 1.0 (/ a-cac a-unc))))))
(System/exit 0)
