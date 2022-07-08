(ns
 ^{:doc "Contains the rules for decompose different types of data for display purposes."}
 arcadia.display.decompose-data
  (:require [arcadia.utility [general :as g] [image :as img] [opencv :as cv]]
            [clojure.reflect :refer [reflect]]
            [clojure.string :as string])
  (:import java.util.ArrayList
           java.awt.image.BufferedImage
           java.awt.Container
           [org.opencv.videoio VideoCapture Videoio]))

(def ^:private python-loaded? "Has libpython been loaded?" (atom false))
(declare python-type get-attr dir) ;; avoid linter warnings if libpython-clj2 is not loaded.
(try
  (require '[libpython-clj2.python :refer [python-type get-attr dir]])
  (reset! python-loaded? true)
  (catch Exception e nil))

(defmacro wp
  "Code gets runs only if libpython has been loaded."
  [code]
  (when @python-loaded?
    code))


(defn simple-literal?
  "Returns true if data is a simple literal (a short value, basically most things
   except non-empty collections or java data structures)."
  [data]
  (or (number? data) (keyword? data) (string? data) (nil? data) (true? data)
      (false? data) (symbol? data) (and (coll? data) (empty? data))
      (instance? java.lang.Character data)))

;;Uses some magic to retrieve a function's metadata, once its namespace is known:
;;https://stackoverflow.com/questions/12432561/how-to-get-the-metadata-of-clojure-function-arguments
(defn- fn->meta
  "Given a function, return its metadata hashmap, if any."
  [f]
  (some-> f class str ;;Find the fn's namespace in its class namestring
          (->> (re-find #"(?<= )[^$]*"))
          (string/replace "_" "-")
          ;;Convert namespace to a symbol and find the function in its namespace
          symbol find-ns ns-map
          (->> (g/seek (fn [[_ v]] (and (var? v) (= f (var-get v))))))
          second meta))

;;Magic for getting the field values comes from here:
;;https://stackoverflow.com/questions/30597085/clojure-how-and-when-to-invoke-the-dot-operator
(defn- get-fields
  "Returns a hashmap describing all field names and values for a java object."
  [obj]
  (when (and obj (not (instance? java.lang.Class obj)))
    (let [fields  (filter (fn [item] (and (= (class item) clojure.reflect.Field)
                                         (:public (.flags item))
                                         (not (:static (.flags item)))))
                         (:members (reflect obj)))]
      (when (seq fields)
        (g/seq-keyfun-valfun->map
         fields
         (fn [field] (keyword (.name field)))
         (fn [field] (.get (.getField (type obj) (str (.name field))) obj)))))))

(defn- data-rank
  "Assigns a rank to data for sorting purposes. Small numbers should be
   sorted first."
  [d]
  (cond
    (map-entry? d) (data-rank (second d))
    (simple-literal? d) 0
    :else 1))

(defn- data-sort-comp
  "Comparator for data for sorting sets and hash-maps."
  [d1 d2]
  (cond
    (< (data-rank d1) (data-rank d2)) -1
    (> (data-rank d1) (data-rank d2)) 1

    (and (simple-literal? d1) (simple-literal? d2))
    (compare (str d1) (str d2))

    (and (map? d1) (map? d2) (:name d1) (:name d2))
    (data-sort-comp (:name d1) (:name d2))

    (and (map-entry? d1) (map-entry? d2))
    (data-sort-comp (first d1) (first d2))

    :else 0))

(defn decompose-data
  "If data can be decomposed, returns a map with keys :pre, a prefix (e.g., \"(\"
   for a list); :post, a postfix (e.g., \")\"); :items, a sequence of items; and
   optionally :name (e.g., the type of a record)."
  [data params]
  (cond
    (record? data)
    (assoc (decompose-data (into {} data) params) :name (g/type-string data))

    (map? data)
    {:pre "{" :post "}"
     :name (when (and (:name data) (:type data)) (str "\"" (:name data) "\""))
     :items
     (-> data
         (cond-> (:cycle (meta data)) (assoc :cycle (:cycle (meta data))))
         (as-> d (apply dissoc d (:excluded-keys params)))
         (->> (sort data-sort-comp)))}

    (set? data) {:pre "#{" :post "}" :items (sort data-sort-comp data)}

    (seq? data) {:pre "(" :post ")" :items data}

    (and (vector? data) (not (map-entry? data)))
    {:pre "[" :post "]" :items data}

    (instance? clojure.lang.Atom data)
    {:pre "(" :post ")" :items (list @data) :name "Atom"}

    (delay? data)
    {:pre "(" :post ")" :items (list (force data)) :name "Delay"}

    (some-> data (.getClass) (.isArray))
    {:pre "(" :post ")" :items (seq data) :name "Array"}

    (= (type data) java.lang.StackTraceElement)
    {:pre "" :post "" :items (-> (.toString data) symbol list)}

    (instance? Exception data)
    (assoc (decompose-data {:message (.getMessage data)
                            :stack-trace (vec (.getStackTrace data))} params)
           :name (g/type-string data))

    (cv/general-mat-type data)
    (let [[width height] (cv/size data)]
      (assoc (decompose-data
              {:width width :height height
               :channels (cv/channels data)
               :depth (cv/depth-symbol data)
               :data (cv/->seq data)}
              params)
             :name (or (cv/mat-name data) (g/type-string data))
             :collapse-children? true))

    (wp (and (= (type data) :pyobject) (= (python-type data) :tuple)))
    (wp (assoc (decompose-data (seq data) params)
           :name "Py:tuple"))

    (wp (and (= (type data) :pyobject) (= (python-type data) :list)))
    (wp (assoc (decompose-data (seq data) params)
               :name "Py:list"))

    (wp (= (type data) :pyobject))
    (wp (let [att-map (g/seq-valfun->map (dir data) #(get-attr data %))]
          (-> att-map
              (g/filter-vals #(#{:ndarray :cuda-gpu-mat :int :float :tuple :list :str}
                               (python-type %)))
              (g/update-all-keywords keyword)
              (assoc
               :defined-methods
               (-> att-map
                   (g/filter-vals #(#{:method-wrapper :builtin-function-or-method}
                                    (python-type %)))
                   keys sort))
              (decompose-data params)
              (assoc :name (str "Py:" (symbol (python-type data)))))))

    (= (type data) BufferedImage)
    (assoc (decompose-data {:width (.getWidth data) :height (.getHeight data)
                            :type (img/bufferedimage-type (.getType data))}
                           params)
           :name "BufferedImage")

    (= (type data) VideoCapture)
    (assoc (decompose-data
            {:frame-height (.get data Videoio/CAP_PROP_FRAME_HEIGHT)
             :frame-width (.get data Videoio/CAP_PROP_FRAME_WIDTH)
             :frame-rate (.get data Videoio/CAP_PROP_FPS)
             :open? (.isOpened data)}
            params)
           :name "VideoCapture")

    (= (type data) ArrayList)
    {:pre "(" :post ")" :items (seq data) :name "ArrayList"}

    (= (type data) clojure.lang.Namespace)
    {:pre "(" :post ")" :items (list (-> data str symbol)) :name "ns"}

    (and (fn? data) (-> data meta :information-function?))
    (assoc (decompose-data
            (meta data)
            params)
           :name "i-fn")

    (fn? data)
    (assoc (decompose-data
            (or (fn->meta data) (list (-> data str symbol)))
            params)
           :name "fn")

    (simple-literal? data)
    nil

    (get-fields data)
    (assoc (decompose-data (get-fields data) params)
           :name (g/type-string data))

    (and (instance? Container data) (seq (.getComponents data)))
    (assoc (decompose-data (seq (.getComponents data)) params)
           :name (g/type-string data))

    :else nil))
