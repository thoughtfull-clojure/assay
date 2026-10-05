(ns systems.thoughtfull.assay.dependencies.errors
  "Error surface: the interface definitions of each component that can
  throw. A definition throws if its body contains throw, or refers to a
  definition that throws, in its own brick or another. Catching isn't
  taken into account, so this is an upper bound."
  (:require
   [systems.thoughtfull.assay.dependencies.cohesion :as cohesion]
   [systems.thoughtfull.assay.dependencies.names :as names]))

(defn- throwing
  "The set of [ns name] definitions that can throw: those that throw, and
  every definition that refers to one, directly or not."
  [measurements]
  (let [sources (mapcat :sources measurements)
        callers (reduce (fn [g {:keys [from ns name]}]
                          (update g [ns name] (fnil conj #{}) from))
                  {}
                  (mapcat cohesion/references sources))
        throwers (for [{:keys [ns definitions]} sources
                       {:keys [name throws?]} definitions
                       :when throws?]
                   [ns name])]
    (loop [frontier (vec throwers)
           seen (set throwers)]
      (if-let [d (peek frontier)]
        (let [new-callers (remove seen (callers d))]
          (recur (into (pop frontier) new-callers) (into seen new-callers)))
        seen))))

(defn analyze
  "Brick name to {:error-surface n}: the component's interface definitions
  that can throw, or nil for a base."
  [{:keys [top-namespace] :as workspace} measurements]
  (let [segments (names/segments top-namespace measurements)
        throws? (throwing measurements)]
    (into {}
      (for [{:keys [brick sources]} measurements
            :let [own (segments (:name brick))]]
        [(:name brick)
         {:error-surface
          (when (= :component (:type brick))
            (count (for [{:keys [ns definitions]} sources
                         :when (names/interface-ns? workspace own ns)
                         {:keys [name]} definitions
                         :when (throws? [ns name])]
                     name)))}]))))
