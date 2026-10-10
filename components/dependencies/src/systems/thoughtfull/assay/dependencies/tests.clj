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
  "Brick name to {:untested-interface r :untested-definitions n}: the share
  and number of the component's public interface definitions that no test
  mentions. A base has neither, and a component without public interface
  definitions no :untested-interface."
  [{:keys [top-namespace] :as workspace} measurements]
  (let [segments (names/segments top-namespace measurements)
        tested (mentioned measurements)]
    (into {}
      (for [{:keys [brick sources]} measurements
            :let [own (segments (:name brick))
                  interface (names/interface-definitions workspace own
                              sources)
                  untested (count (remove #(tested [(:ns %) (:name %)])
                                    interface))]]
        [(:name brick)
         (when (= :component (:type brick))
           {:untested-interface (when (seq interface)
                                  (/ untested (double (count interface))))
            :untested-definitions untested})]))))

(defn boundary-crossings
  "Each require, in a brick's tests, of another brick's source namespace
  other than its interface. Another brick's test namespaces, such as
  shared generators, don't count."
  [{:keys [workspace bricks]}]
  (let [top-ns (:top-namespace workspace)
        segments (names/segments top-ns bricks)
        test-nss (set (keep :ns (mapcat :tests bricks)))]
    (for [{:keys [brick tests]} bricks
          :let [own (segments (:name brick))]
          {:keys [file requires]} tests
          {:keys [ns line]} requires
          :let [s (names/segment top-ns ns)]
          :when (and s (not= s own)
                  (not (test-nss ns))
                  (not (names/interface-ns? workspace s ns)))]
      {:brick brick
       :subject (str file " " ns)
       :location {:file file :line line}
       :message (str "requires " ns ", inside " s
                  "; test through its interface instead")})))
