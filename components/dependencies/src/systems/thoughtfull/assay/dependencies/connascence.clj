(ns systems.thoughtfull.assay.dependencies.connascence
  "Connascence between bricks: the static kinds that can be read from
  source. Connascence within a brick is expected; between bricks it is
  coupling.

  - Position: interface functions that other bricks call with many
    positional parameters, so callers depend on their order.
  - Meaning: keywords used in more than one brick, which are usually map
    keys that the bricks must agree on.
  - Algorithm: the same code, of at least some size, in more than one
    brick."
  (:require
   [clojure.string :as str]
   [systems.thoughtfull.assay.dependencies.names :as names]))

(defn shared-keywords
  "For each brick name, how many of its keywords another brick also uses."
  [measurements]
  (let [keywords-of (into {}
                      (for [{:keys [brick sources]} measurements]
                        [(:name brick) (into #{} (mapcat :keywords) sources)]))
        brick-counts (frequencies (mapcat val keywords-of))]
    (update-vals keywords-of
      (fn [ks] (count (filter #(< 1 (brick-counts %)) ks))))))

(defn positional-interface
  "Interface functions of components that other bricks use, with their
  positional parameters as their value."
  [workspace measurements used]
  (let [segments (names/segments (:top-namespace workspace) measurements)]
    (for [{:keys [brick sources functions]} measurements
          :when (= :component (:type brick))
          :let [own (segments (:name brick))
                interface-files (set (keep #(when (names/interface-ns?
                                                    workspace own (:ns %))
                                              (:file %))
                                       sources))]
          {:keys [name file line params]} functions
          :when (and (interface-files file)
                  (used [own (symbol name)]))]
      {:brick brick
       :subject name
       :value params
       :location {:file file :line line :name name}
       :message (str "has " params " positional parameters, and other bricks"
                  " call it; consider taking a map")})))

(defn- occurrences
  [limit measurements]
  (for [{:keys [brick sources]} measurements
        {:keys [file fragments]} sources
        fragment fragments
        :when (> (:forms fragment) limit)]
    (assoc fragment :brick brick :file file)))

(defn- contained?
  "True if occurrence o is inside a larger duplicated occurrence in the same
  file."
  [duplicated o]
  (some #(and (= (:file %) (:file o))
           (> (:forms %) (:forms o))
           (<= (:line %) (:line o))
           (>= (:end-line %) (:end-line o)))
    duplicated))

(defn duplicates
  "Code of more than limit forms that appears in more than one brick, with
  its forms as its value. Only the largest duplicated form is reported,
  not the forms inside it."
  [limit measurements]
  (let [by-hash (group-by :hash (occurrences limit measurements))
        duplicated (filter #(< 1 (count (set (map (comp :name :brick) %))))
                     (vals by-hash))
        all-duplicated (apply concat duplicated)]
    (for [group duplicated
          o group
          :when (not (contained? all-duplicated o))
          :let [others (remove #(= (:brick %) (:brick o)) group)
                other (first others)]]
      {:brick (:brick o)
       :subject (str (:hash o))
       :value (:forms o)
       :location {:file (:file o) :line (:line o)}
       :message (str "duplicates " (:forms o) " forms in "
                  (str/join ", " (sort (set (map (comp :name :brick) others))))
                  " (" (:file other) ":" (:line other) ")")})))
