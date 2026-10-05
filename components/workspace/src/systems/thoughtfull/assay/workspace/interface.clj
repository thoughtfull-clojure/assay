(ns systems.thoughtfull.assay.workspace.interface
  "Discover the bricks of a Polylith workspace."
  (:require
   [systems.thoughtfull.assay.workspace.core :as core]))

(defn bricks
  "The bricks (components and bases) of the Polylith workspace at root, sorted
  by type then name. Each is a map of :name, :type (:component or :base),
  :dir (relative to root), :files, the brick's Clojure source files under
  src, and :test-files, those under test, all relative to root."
  [root]
  (core/bricks root))

(defn config
  "The Polylith settings of the workspace at root that assay uses, from
  workspace.edn: :top-namespace (a string, or nil) and :interface-ns
  (default \"interface\")."
  [root]
  (core/config root))
