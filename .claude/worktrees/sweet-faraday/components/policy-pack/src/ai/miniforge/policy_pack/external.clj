(ns ai.miniforge.policy-pack.external
  "External PR evaluation workflow for read-only policy checking.

   Parses PR diffs into artifacts, selects applicable packs, and runs
   evaluation in read-only mode (no repair phase).

   Layer 0: Diff parsing
   Layer 1: Pack selection
   Layer 2: Evaluation and reporting"
  (:require
   [clojure.string :as str]
   [ai.miniforge.policy-pack.core :as core]))

;------------------------------------------------------------------------------ Layer 0
;; Diff parsing

(defn parse-diff-header
  "Parse a diff header to extract the file path.
   Handles 'diff --git a/path b/path' format."
  [header-line]
  (when-let [[_ path] (re-find #"^diff --git a/.+ b/(.+)$" header-line)]
    path))

(defn parse-pr-diff
  "Parse a unified diff into a vector of artifact maps.

   Arguments:
   - diff-content - String containing unified diff output

   Returns:
   [{:artifact/path string
     :artifact/content string   ;; the new file content (+ lines)
     :artifact/diff string}]    ;; the raw diff hunk"
  [diff-content]
  (when (and diff-content (not (str/blank? diff-content)))
    (let [lines (str/split-lines diff-content)
          ;; Split on diff headers
          segments (reduce
                    (fn [acc line]
                      (if (str/starts-with? line "diff --git")
                        (conj acc {:header line :lines []})
                        (if (seq acc)
                          (update-in acc [(dec (count acc)) :lines] conj line)
                          acc)))
                    []
                    lines)]
      (vec
       (keep (fn [{:keys [header lines]}]
               (when-let [path (parse-diff-header header)]
                 (let [diff-text (str/join "\n" lines)
                       ;; Extract added lines (content approximation)
                       added-lines (->> lines
                                        (filter #(str/starts-with? % "+"))
                                        (remove #(str/starts-with? % "+++"))
                                        (map #(subs % 1)))]
                   {:artifact/path path
                    :artifact/content (str/join "\n" added-lines)
                    :artifact/diff diff-text})))
             segments)))))

;------------------------------------------------------------------------------ Layer 1
;; Pack selection

(defn path-matches-glob?
  "Test a single path against a single glob pattern."
  [glob-fn glob path]
  (try (glob-fn glob path) (catch Exception _ false)))

(defn files-match-globs?
  "True if any path in `paths` matches any pattern in `globs`."
  [paths globs]
  (let [glob-fn @(requiring-resolve 'ai.miniforge.policy-pack.registry/glob-matches?)]
    (some (fn [path]
            (some #(path-matches-glob? glob-fn % path) globs))
          paths)))

(defn pack-applies?
  "True if a pack applies to the given changed files.
   Packs with no :file-globs constraint apply to everything."
  [changed-files pack]
  (let [file-globs (get-in pack [:pack/applies-to :file-globs])]
    (or (not (seq file-globs))
        (files-match-globs? changed-files file-globs))))

(defn select-applicable-packs
  "Select packs applicable to a PR based on metadata.

   Arguments:
   - packs - Vector of PackManifest maps
   - pr-meta - Map with :repo, :labels, :base-branch, :changed-files

   Returns: Vector of applicable packs."
  [packs pr-meta]
  (let [changed-files (get pr-meta :changed-files [])]
    (filterv (partial pack-applies? changed-files) packs)))

;------------------------------------------------------------------------------ Layer 2
;; Evaluation and reporting

(defn evaluate-external-pr
  "Evaluate an external PR against policy packs in read-only mode.

   Arguments:
   - packs - Vector of PackManifest maps (or single pack)
   - pr-data - Map with:
     - :diff - Raw unified diff string
     - :artifacts - Pre-parsed artifacts (alternative to :diff)
     - :repo - Repository identifier
     - :pr-number - PR number
     - :labels - Vector of label strings
     - :changed-files - Vector of changed file paths

   Returns:
   {:evaluation/passed? bool
    :evaluation/violations [{:rule-id kw :severity kw :message str :path str}]
    :evaluation/summary {:critical int :major int :minor int :info int :total int}
    :evaluation/artifacts-checked int
    :evaluation/packs-applied [string]}"
  [packs pr-data]
  (let [packs-vec (if (vector? packs) packs [packs])
        applicable (select-applicable-packs packs-vec pr-data)
        artifacts (or (:artifacts pr-data)
                      (parse-pr-diff (:diff pr-data))
                      [])
        context {:read-only? true
                 :phase :review
                 :pr-meta (select-keys pr-data [:repo :pr-number :labels])}
        ;; Check each artifact against applicable packs
        results (mapv (fn [artifact]
                        (core/check-artifact applicable artifact context))
                      artifacts)
        ;; Aggregate violations
        all-violations (mapcat :violations results)
        severity-counts (merge {:critical 0 :major 0 :minor 0 :info 0}
                               (frequencies (map :severity all-violations)))
        total (count all-violations)
        has-blocking? (some (comp seq :blocking) results)]
    {:evaluation/passed? (not has-blocking?)
     :evaluation/violations (vec all-violations)
     :evaluation/summary (assoc severity-counts :total total)
     :evaluation/artifacts-checked (count artifacts)
     :evaluation/packs-applied (mapv :pack/id applicable)}))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Parse a diff
  (parse-pr-diff
   "diff --git a/main.tf b/main.tf
--- a/main.tf
+++ b/main.tf
@@ -1,3 +1,4 @@
 resource \"aws_vpc\" \"main\" {
+  enable_dns_hostnames = true
   cidr_block = var.vpc_cidr
 }
diff --git a/variables.tf b/variables.tf
--- a/variables.tf
+++ b/variables.tf
@@ -1,2 +1,3 @@
+variable \"new_var\" {}
 variable \"vpc_cidr\" {}")

  :leave-this-here)
