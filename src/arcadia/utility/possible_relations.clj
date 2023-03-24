(ns
  ^{:author "Andrew Lovett"
    :doc "Functions for working with possible relations in working memory."}
  arcadia.utility.possible-relations
  (:require [arcadia.utility.objects :refer [get-region]]
            [arcadia.utility.geometry :as geo]))

(defn make-possible-relation
  "Extracts the key information for a possible relation to be stored in WM."
  [relation obj1 obj2 value]
  {:name "possible-relation"
   :arguments
   {:predicate (:predicate relation)
    :obj1-descriptor (:obj1-descriptor relation)
    :obj2-descriptor (:obj2-descriptor relation)

    :context (:context relation)
    :value value
    :obj1 obj1
    :obj2 obj2

    :obj1-name (:obj1-name relation)
    :obj2-name (:obj2-name relation)
    :predicate-name (:predicate-name relation)}
   :world nil
   :type "possibility"})

(defn same-possible-relations?
  "Checks whether these possible-relations are describing the same thing."
  [rel1 rel2]
  (let [args1 (or (:arguments rel1) rel1)
        args2 (or (:arguments rel2) rel2)]
    (and
;;       (= (:name rel1) "possible-relation")
;;       (= (:name rel2) "possible-relation")
      (= (:predicate args1) (:predicate args2))
      (= (:obj1-descriptor args1) (:obj1-descriptor args2))
      (= (:obj2-descriptor args1) (:obj2-descriptor args2))
      (= (:context args1) (:context args2)))))


(defn possible-relation->text
  "Generates text to describe a possible relation."
  ([rel simple?]
   (if simple?
     (str "(" (:value (:arguments rel)) "): "
          (get-in rel [:arguments :obj1-name] "Obj 1") " "
          (get-in rel [:arguments :predicate-name]
                  (get-in rel [:arguments :predicate] "undefined")) " "
          (get-in rel [:arguments :obj2-name] "obj 2"))

     (str "(" (:context (:arguments rel)) "," (:value (:arguments rel)) "): "
          (get-in rel [:arguments :obj1-name] "Obj 1") " "
          (get-in rel [:arguments :predicate-name]
                  (get-in rel [:arguments :predicate] "undefined")) " "
          (get-in rel [:arguments :obj2-name] "obj 2"))))
  ([rel]
   (possible-relation->text rel false)))

;;For every key in map1, is its value the same in both maps?
(defn- map-subset? [map1 map2]
  (every? #(= (% map1) (% map2)) (keys map1)))

;;Object descriptors are maps that must match an object for it to be selected.
;;Generally speaking, that means every key/value pair found in the map should also
;;be in the object's arguments map. However, we're allowing for special keywords
;;that will be compared against the object's location.
(defn object-matches-descriptor?
  "Does the object descriptor match the object?"
  [object descriptor content]
  (let [min-x (:min-x descriptor)
        max-x (:max-x descriptor)
        min-y (:min-y descriptor)
        max-y (:max-y descriptor)
        {x :x y :y} (geo/center (get-region object content))

        descriptor (dissoc descriptor :min-x :max-x :min-y :max-y)]
    (and (map-subset? descriptor (:arguments object))
         (or (nil? max-x)
             (< x max-x))
         (or (nil? min-x)
             (> x min-x))
         (or (nil? max-y)
             (< y max-y))
         (or (nil? min-y)
             (> y min-y)))))
