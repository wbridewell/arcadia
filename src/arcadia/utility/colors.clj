(ns
  ^{:doc "Support for referencing colors via symbols like :red or :green or strings like \"red\"."}
  arcadia.utility.colors
  (:require [arcadia.utility.general :as g])
  (:import java.awt.Color))

(defn- jcolor->rgb
  [^Color jcolor]
  [(.getRed jcolor) (.getGreen jcolor) (.getBlue jcolor)])

(def ^:private keyword->rgb
  "Hashmap mapping color keywords to [r g b] vectors (in bytes)"
  {:black (jcolor->rgb Color/black) 
   :blue (jcolor->rgb Color/blue)
   :cyan [0 188 227] ;(jcolor->rgb Color/cyan)
   :dark-gray (jcolor->rgb Color/darkGray)
   :dark-grey (jcolor->rgb Color/darkGray)
   :gold [255 215 0]
   :gray (jcolor->rgb Color/gray)
   :grey (jcolor->rgb Color/gray)
   :green (jcolor->rgb Color/green)
   :light-blue [173 216 230]
   :light-gray (jcolor->rgb Color/lightGray)
   :light-grey (jcolor->rgb Color/lightGray)
   :light-green [144 238 144]
   :magenta (jcolor->rgb Color/magenta)
   :orange (jcolor->rgb Color/orange) 
   :pink (jcolor->rgb Color/pink)
   :purple [128 0 128]
   :red (jcolor->rgb Color/red)
   :white (jcolor->rgb Color/white)
   :yellow (jcolor->rgb Color/yellow)
   })

(def ^:private keyword->bgr
  "Hashmap mapping color keywords to [b g r] vectors (in bytes)"
  (g/update-all keyword->rgb (fn [[r g b]] (vector b g r))))



(defmulti ->rgb "Return an [r g b] vector for a color, such as :red. Format is unsigned bytes."
  type)

(defmethod ->rgb clojure.lang.Keyword 
  [color]
  (if-let [values (keyword->rgb color)]
    values
    (throw (Exception. (str "Color keyword is not recognized: " color)))))

(defmethod ->rgb java.lang.String
  [color]
  (->rgb (keyword color)))

(defmethod ->rgb clojure.lang.PersistentVector
  [color]
  color)

(defmethod ->rgb Color
  [^Color color]
  [(.getRed color) (.getGreen color) (.getBlue color)])



(defmulti ->bgr "Return a [b g r] vector for a color, such as :red. Format is unsigned bytes."
  type)

(defmethod ->bgr clojure.lang.Keyword
  [color]
  (if-let [values (keyword->bgr color)]
    values
    (throw (Exception. (str "Color keyword is not recognized: " color)))))

(defmethod ->bgr java.lang.String
  [color]
  (->bgr (keyword color)))

(defmethod ->bgr clojure.lang.PersistentVector
  [[r g b]]
  [b g r])

(defmethod ->bgr Color
  [^Color color]
  [(.b color) (.g color) (.r color)])



(defmulti ->java 
  "Return a java.awt.Color data structure for a color, such as :red. If the inpute is a vector,
   assumes it is in [r g b] bytes."
  type)

(defmethod ->java clojure.lang.Keyword
  [color]
  (->java (->rgb color)))

(defmethod ->java java.lang.String
  [color]
  (->java (keyword color)))

(defmethod ->java Color
  [color]
  color)

(defmethod ->java clojure.lang.PersistentVector
  [[r g b]]
  (Color. r g b))