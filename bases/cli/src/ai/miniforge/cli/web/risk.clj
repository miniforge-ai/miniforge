(ns ai.miniforge.cli.web.risk
  "PR risk analysis - pure functions."
  (:require [clojure.string :as str]))

(def colors
  {:low "#22c55e"
   :medium "#eab308"
   :high "#ef4444"})

(def bg-colors
  {:low "rgba(34, 197, 94, 0.1)"
   :medium "rgba(234, 179, 8, 0.1)"
   :high "rgba(239, 68, 68, 0.1)"})

(defn title-contains? [title-lower & terms]
  (some #(str/includes? title-lower %) terms))

(defn classify-risk [total-changes file-count is-docs? is-deps? is-refactor?]
  (cond
    (and is-docs? (< total-changes 100)) :low
    (and is-deps? (< file-count 3)) :low
    (and (< total-changes 50) (< file-count 3)) :low
    (> total-changes 500) :high
    (> file-count 20) :high
    (and is-refactor? (> total-changes 200)) :high
    :else :medium))

(defn classify-complexity [total-changes]
  (cond
    (< total-changes 20) :trivial
    (< total-changes 100) :simple
    (< total-changes 300) :moderate
    :else :complex))

(defn summarize-type [is-docs? is-deps? is-fix? is-refactor? is-feature?]
  (cond
    is-docs? "Documentation update"
    is-deps? "Dependency version bump"
    is-fix? "Bug fix"
    is-refactor? "Code refactoring"
    is-feature? "New feature"
    :else "Code changes"))

(defn build-reasons [total-changes file-count is-refactor?]
  (cond-> []
    (> total-changes 300) (conj (str total-changes " lines changed"))
    (> file-count 10) (conj (str file-count " files modified"))
    is-refactor? (conj "Refactoring changes")))

(defn analyze-pr [{:keys [title additions deletions changedFiles]}]
  (let [total-changes (+ (or additions 0) (or deletions 0))
        file-count (or changedFiles 0)
        title-lower (str/lower-case (or title ""))
        is-deps? (title-contains? title-lower "bump" "deps" "dependency")
        is-docs? (title-contains? title-lower "readme" "docs" "documentation")
        is-fix? (title-contains? title-lower "fix")
        is-refactor? (title-contains? title-lower "refactor")
        is-feature? (title-contains? title-lower "add" "feat" "implement")
        risk (classify-risk total-changes file-count is-docs? is-deps? is-refactor?)]
    {:risk risk
     :complexity (classify-complexity total-changes)
     :summary (summarize-type is-docs? is-deps? is-fix? is-refactor? is-feature?)
     :suggested-action (case risk
                         :low "Safe to merge"
                         :medium "Review recommended"
                         :high "Careful review needed")
     :reasons (build-reasons total-changes file-count is-refactor?)
     :total-changes total-changes
     :file-count file-count}))
