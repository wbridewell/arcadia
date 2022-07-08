(ns arcadia.component.conceptual-ltm
  "This component stores conceptual representations for the purposes of enabling
   recognition and recollection. The stored information supports the production 
   of familiarity signals and scene reconstruction.

  Focus Responsive
    * memorize
    * memory-retrieval

  Produces an element for ...

  Default Behavior
  None

  Produces
   ..."
  (:require [arcadia.utility.descriptors :as d]
            [arcadia.component.core :refer [Component merge-parameters]]))

(defn- conceptualize 
  "Only keep the symbolic content of the element's arguments and remove source and world information."
  [element]
  (dissoc
   (update element :arguments
           (fn [m]
             (into {} (filter #((some-fn string? symbol? keyword?) (val %)) m))))
   :source :world))

;; return a version of the element where any arguments that are objects have been
;; conceptualized and a sequence of those conceptualized elements that can be 
;; added to LTM
(defn- record-objects!
  [database element]
  (cond
    (= (:name element) "object")
    (swap! database conj (conceptualize element))

  ;; HACK: visual-new-object and visual-equality elements have an :origin field that will 
  ;; get recorded as a concept.
  ;; it's probably not harmful, but it creates clutter, so we are ignoring that for now
    (#{"visual-new-object" "visual-equality"} (:name element))
    (swap! database #(apply conj %1 %2)
           (map (comp conceptualize val)
                (filter #(d/element-matches? (val %) :name "object") (dissoc (:arguments element) :origin))))
    :else
    (swap! database #(apply conj %1 %2)
           (map (comp conceptualize val)
                (filter #(d/element-matches? (val %) :name "object") (:arguments element))))))

;; return a version of the object where any arguments that contain objects are replaced by 
;; representations that can be found in the database.
;; using this function will result in objects that are #= to those in the database, but they
;; will not be #identical? to them. space could be saved if they were, but it's unlikely to
;; be a problem in general.
(defn- replace-objects
  [element]
  (let [objects (into {} (map #(vector (key %) ((comp conceptualize val) %))
                              (filter #(d/element-matches? (val %) :name "object") 
                                      (:arguments element))))]
    (update (conceptualize element) :arguments #(merge % objects))))

(defn- recall-element
  [x component]
  (when x
    (assoc x :source component :world "memory")))

;; Store objects and relations among objects
;; Objects will be stripped of their non-symbolic arguments
;; Other interlingua element types will be stripped of their non-symbolic arguments 
;; except for when the arguments are objects, in which case those
;; objects which will be stored in LTM as above (if not already there) and
;; then the stored values will be linked to the relations.
(defrecord ConceptualLTM [buffer database]
  Component

  (receive-focus
   [component focus content]
   (reset! (:buffer component) nil)
   (cond
     ;; do we retrieve all elements or an arbitrary matching one?
     (d/element-matches? focus :name "memory-retrieval" :type "action" :world nil)
     (when-let [query (-> focus :arguments :descriptor)]
       (reset! (:buffer component)
               (recall-element
                (d/rand-match query (seq @(:database component)))
                component)))

     ;; store an object in LTM when it is explicitly stored in working memory.
     (d/element-matches? focus :name "memorize" :type "action" :world nil
                         :element #(d/element-matches? % :name "object"))
      ;; store a simplified representation of the object
     (record-objects! (:database component) (-> focus :arguments :element))

     ;; store an element in LTM when it is explicitly stored in working memory.
     ;; this is useful for storing relations.
     (d/element-matches? focus :name "memorize" :type "action" :world nil)
      ;; store a simplified representation of the object
     (doto (:database component)
       (record-objects! (-> focus :arguments :element))
       (swap! conj (replace-objects (-> focus :arguments :element))))))
     
  (deliver-result
   [component]
   (set @(:buffer component))))

(defmethod print-method ConceptualLTM [comp ^java.io.Writer w]
  (.write w (format "ConceptualLTM{}")))

(defn start [& {:as args}]
  (let [p (merge-parameters args)]
    (->ConceptualLTM (atom nil) (atom #{}))))