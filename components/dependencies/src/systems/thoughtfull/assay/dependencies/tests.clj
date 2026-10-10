(ns systems.thoughtfull.assay.dependencies.tests
  "Tests seen from the workspace: interface definitions no test mentions,
  and tests that reach into another brick's implementation rather than
  its interface."
  (:require
   [systems.thoughtfull.assay.dependencies.cohesion :as cohesion]
   [systems.thoughtfull.assay.dependencies.names :as names]))

(defn- mentioned
  "The set of [ns name] definitions that any test in the workspace refers
  to, through its aliases and refers."
  [measurements]
  (into #{}
    (for [{:keys [tests]} measurements
          test tests
          :let [resolve (cohesion/resolver test)]
          sym (:references test)
          :let [target (resolve sym)]
          :when target]
      target)))

(defn analyze
  "Brick name to {:untested-interface n}: the component's interface
  definitions that no test mentions, or nil for a base."
  [{:keys [top-namespace] :as workspace} measurements]
  (let [segments (names/segments top-namespace measurements)
        tested (mentioned measurements)]
    (into {}
      (for [{:keys [brick sources]} measurements
            :let [own (segments (:name brick))]]
        [(:name brick)
         {:untested-interface
          (when (= :component (:type brick))
            (count (for [{:keys [ns definitions]} sources
                         :when (names/interface-ns? workspace own ns)
                         {:keys [name]} definitions
                         :when (not (tested [ns name]))]
                     name)))}]))))

(defn boundary-crossings
  "Each require, in a brick's tests, of another brick's namespace other
  than its interface."
  [{:keys [workspace bricks]}]
  (let [top-ns (:top-namespace workspace)
        segments (names/segments top-ns bricks)]
    (for [{:keys [brick tests]} bricks
          :let [own (segments (:name brick))]
          {:keys [file requires]} tests
          {:keys [ns line]} requires
          :let [s (names/segment top-ns ns)]
          :when (and s (not= s own)
                  (not (names/interface-ns? workspace s ns)))]
      {:brick brick
       :subject (str file " " ns)
       :location {:file file :line line}
       :message (str "requires " ns ", inside " s
                  "; test through its interface instead")})))
