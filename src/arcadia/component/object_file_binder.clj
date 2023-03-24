(ns arcadia.component.object-file-binder
  "According to feature integration theory, attending to a visual region
  containing an object enables the binding of that object's features into an
  integrated representation. The durations and (featural) limitations of the
  resulting object files are continual areas of study.

  This component binds visual features along with features associated with
  object motion, semantics, and other properties, erring on the side of being
  overly complete.

  Focus Responsive
    * fixation

  Binds together the features associated with the proto-object (if any) at the
  location specified by the fixation request.

  Default Behavior
  None.

  Produces
   * object
       includes an unbounded set of arguments that will typically include
       :region, which is the rectangular bounding region of the object
       :mask, which is a mask matrix for the pixels in the region that are part
              of the object
       :image, which is a color image of the object
       :area, which is the calculated area of the object
       :slot, which is the VSTM slot occupied by the object file (if any)
       :tracked?, which indicates whether the object is visually tracked

  NOTE: The color field is not a semantic identifier. Instead, it
  corresponds to a color view of the proto-object's region. This object
  is a precursor to the abstract characterization of the object and
  as such is tied more directly to perceptual input."
  (:require [arcadia.component.core :refer [Component]]
            [arcadia.utility [descriptors :as d] [objects :as obj]]))

(defn- grab-arguments
  "Extracts properties as an argument-value map from the given element."
  [element]
  (cond (-> element :arguments :segment)
        (dissoc (:arguments element) :segment)

        (= (:name element) "object-property")
        {(-> element :arguments :property)
         (-> element :arguments :value)}

        (or (= (:name element) "object")
            (and (= (:name element) "fixation")
                 (-> element :arguments :object)))
        (dissoc (:arguments element) :object)))

(defn- assign-slot
  [fixation object content]
  (or (-> object :arguments :slot)
      (-> (d/first-element (obj/get-latest-locations content)
                           :region (-> fixation :arguments :segment :region))
          :arguments :slot)
      :unallocated))

(defn- compose-object [segment slot descriptors]
  {:name "object"
   :arguments (apply merge (conj (map grab-arguments descriptors)
                                 (assoc segment
                                        :slot (or slot :unallocated)
                                        :tracked? true)))
   :type "instance"})

(defrecord ObjectFileBinder [buffer]
  Component
  (receive-focus
    [component focus content]
    (reset! (:buffer component) nil)
    (when (d/element-matches? focus :name "fixation")
      (let [object (-> focus :arguments :object (obj/updated-object content))]
        (when-let [segment (obj/get-segment (or object focus) content)]
          (reset! (:buffer component)
                  (assoc (compose-object segment
                                         (assign-slot focus object content)
                                         (concat (filter #(and (= (:world focus) (:world %))
                                                               (= (obj/get-region % content) (:region segment)))
                                                         content)
                                                 (list focus)))
                         :world (:world focus)))))))
  (deliver-result
    [component]
    (list @buffer)))

(defmethod print-method ObjectFileBinder [comp ^java.io.Writer w]
  (.write w (format "ObjectFileBinder{}")))

(defn start []
  (->ObjectFileBinder (atom nil)))
