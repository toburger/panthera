(ns panthera.pandas.utils
  (:require
   [libpython-clj.python :as py]
   [libpython-clj.require :refer [require-python]]
   [camel-snake-kebab.core :as csk]
   [camel-snake-kebab.extras :as cske]
   [clojure.core.memoize :as m]))

(py/initialize!)

(require-python '[builtins :as bt])

;(defonce builtins (py/import-module "builtins"))
(defonce pd (py/import-module "pandas"))

(defn slice
  "Returns a Python slice. This is what you'd get by doing something like
  `1:10` and it is similar to `(range 1 10)`, but works with everything
  not only numbers, so `(slice \"a\" \"f\")` would mean
  [\"a\" \"b\" \"c\" \"d\" \"e\" \"f\"]. Use this for subsetting arrays,
  serieses and data-frames.

  Example:

  ```
  (slice) ; the empty slice, it means every index

  (slice 5) ; every index up to 5

  (slice 3 5) ; every index from 3 to 5

  (slice \"2019-10-11\" \"2019-12-3\") ; works with dates as well

  (slice \"a\" \"d\") ; works with strings

  (slice 1 10 2) ; every 2 values between 1 and 10
  ```"
  ([]
   (bt/slice nil))
  ([start]
   (bt/slice start))
  ([start stop]
   (bt/slice start stop))
  ([start stop incr]
   (bt/slice start stop incr)))

(defn pytype
  "Return the Python type of the given objects

  Examples:

  ```
  (pytype obj)

  (pytype my-df my-srs this)
  ```"
  ([] nil)
  ([obj]
   (py/python-type obj))
  ([obj & objs]
   (map pytype (concat (vector obj) objs))))

(def memo-key-converter
  "Convert regular Clojure kebab-case keys to idiomatic
  Python snake_case strings.

  Example:

  ```
  (memo-key-converter :a-key) ; \"a_key\"
  ```"
  (m/fifo csk/->snake_case_string {} :fifo/threshold 512))

(def memo-columns-converter
  "Converts Python strings to idiomatic Clojure keys.

  Examples:

  ```
  (memo-columns-converter \"a_name\") ; :a-name

  (memo-columns-converter \"ALL_CAPS\") ; :all-caps
  ```"
  (m/fifo
    #(cond
       (number? %) %
       (string? %) (csk/->kebab-case-keyword %)
       (nil? %) nil
       :else (mapv csk/->kebab-case-keyword %)) {} :fifo/threshold 512))

(defn vec->pylist
  "Converts an iterable Clojure data structure to a Python list

  Example:

  ```
  (vec->pylist my-df)
  ```"
  [v]
  (py/->py-list v))

(defn nested-vector?
  "Check if the given argument is a nested vector or not.

  Example:

  ```
  (nested-vector? [[1 2] [3 4]])
  ```"
  [v]
  (some vector? v))

(defn nested-slice?
  "Check if the given value contains at least one `:slice`.

  Example:

  ```
  (nested-slice? [(slice 3 5) (slice)])
  ```"
  [v]
  (some #(identical? :slice (pytype %)) v))

(defn vals->pylist
  "Takes some values and dispatches them to the right conversion to a Python
  data structure.

  Examples:

  ```
  (vals->pylist [1 2 3])

  (vals->pylist [[1 2] [3 4]])

  (vals->pylist [(slice 1 5) (slice)])
  ```"
  [obj]
  (cond
    (not (coll? obj)) obj
    (map? obj) obj
    (nested-vector? obj) (to-array-2d obj)
    (vector? obj) (if (nested-slice? obj)
                    obj
                    (py/->py-list obj))
    :else obj))

(defn keys->pyargs
  "Takes a map as an argument and converts keys to Python strings
  and values to the proper data structure.

  Examples:

  ```
  (keys->pyargs {:a 1 :a-key [1 2 3] \"c\" (slice)})
  ```"
  [m]
  (let [nm (reduce-kv
             (fn [m k v]
               (assoc m k (vals->pylist v)))
             {} m)]
    (cske/transform-keys memo-key-converter nm)))

(defn series?
  "Check if the given argument is a series"
  [obj]
  (identical? :series (pytype obj)))

(defn data-frame?
  "Check if the given argument is a data-frame"
  [obj]
  (identical? :data-frame (pytype obj)))

(defmulti to-clj
  (fn [obj] (series? obj)))

(defmethod to-clj false
  [obj]
  {:id (py/get-attr obj "index")
   :columns (py/get-attr obj "columns")
   :data (lazy-seq (py/get-attr obj "values"))}
  (comment (->DATASET
             (py/get-attr obj "index")
             (py/get-attr obj "columns")
             (lazy-seq (py/get-attr obj "values")))))

(defmethod to-clj true
  [obj]
  {:id      (py/get-attr obj "index")
   :columns (or (py/get-attr obj "name") "unnamed")
   :data    (lazy-seq (py/get-attr obj "values"))}
  (comment
    (->DATASET
      (py/get-attr obj "index")
      (or (py/get-attr obj "name") "unnamed")
      (lazy-seq (py/get-attr obj "values")))))

(defn ->clj
  "Convert the given panthera data-frame or series to a Clojure vector of maps.
  The idea is to have a common, simple and fast access point to conversion of
  the main data structures between languages.

  - `series`: a `series` gets converted to a vector of maps with only one key and
  one value. If the series has a name that becomes the key of the maps,
  otherwise `->clj` falls back to the `:unnamed` key.
  - `data-frame`: a `data-frame` is converted to a vector of maps with names
  of the columns as keys and values as the corresponding row/column value.

  With the default method you might incur a data loss: the index doesn't get
  converted and in case you're using a hierarchical index you get only one level
  out of it. To keep everything in one place you have to make `full?` true, in
  this way you get back a map with keys `{:id :cols :data}`.

  **Arguments**

  - `df-or-srs` -> `data-frame` or `series`
  - `full?` -> whether to use the full conversion

  **Examples**

  ```
  (->clj my-srs)

  (->clj my-df)
  ```
  "
  [df-or-srs & [full?]]
  (if full?
    (to-clj df-or-srs)
    (if (series? df-or-srs)
      (let [nm (memo-columns-converter
                 (or (py/get-attr df-or-srs "name")
                   "unnamed"))]
        (into [] (map #(assoc {} nm %))
          (vec df-or-srs)))
      (let [ks (map memo-columns-converter
                 (py/get-attr df-or-srs "columns"))]
        (into [] (map #(zipmap ks %))
          (py/get-attr df-or-srs "values"))))))

(defn simple-kw-call
  "Helper for a cleaner access to `call-attr-kw` from `libpython-clj`"
  [df kw & [attrs]]
  (py/call-attr-kw df kw []
                   (keys->pyargs attrs)))

(defn kw-call
  "Helper for a cleaner access to `call-attr-kw` from `libpython-clj`"
  [df kw pos & [attrs]]
  (py/call-attr-kw df kw [(vals->pylist pos)]
                   (keys->pyargs attrs)))
