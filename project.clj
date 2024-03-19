(defproject panthera "0.1-alpha.20"
  :description "Data Frames in Clojure (with Pandas) + NumPy"
  :url "https://github.com/alanmarazzi/panthera"
  :scm {:name "git" :url "https://github.com/alanmarazzi/panthera"}
  :license {:name "EPL-2.0"
            :url  "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[clj-python/libpython-clj "2.025"]
                 [techascent/tech.parallel "2.11"]
                 [org.clojure/core.memoize "1.1.266"]
                 [camel-snake-kebab "0.4.3"]]
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.11.1"]]}})

