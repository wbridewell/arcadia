(ns arcadia.component.highlighter.proximity
  "When you're focusing on an object crowded by a segment NOT
  already in VSTM, request a fixation on that segment to put it
  into VSTM. Allows robust detection of collisions.

  Focus Selective
   * fixation, object, scan- if the focused object is in very close proximity
                             to another segment, produce a fixation to the segment.

  Default Behavior
    None.

  Produces
    * fixation to a proto-object proximal to the focus"
  (:require [arcadia.component.core :refer [Component merge-parameters]]
            (arcadia.utility [descriptors :as d] [general :as g] [objects :as obj])
            [arcadia.vision.regions :as reg]))

(def ^:parameter max-dist "maximum crowding distance between the objects in pixels" 5.0)

(defn- make-fixation [segment focus-object component]
  {:name "fixation"
   :arguments {:reason "proximity"
               :segment segment
               :target focus-object}
   :type "instance"
   :source component
   :world nil})

(defn- at-loc
  "Consider a segment to be at a location if it's bounding box
  intersects the location's bounding box"
  [segment location]
  (reg/intersect? (:region segment) (-> location :arguments :region)))

(defrecord ProximityHighlighter [buffer max-dist] Component
  (receive-focus
   [component focus content]
   (reset! (:buffer component) nil)
   (let [vstm (obj/get-vstm-objects content :tracked? true)
         obj (or (and (= (:name focus) "object")
                      focus)
                 (and (= (:name focus) "fixation")
                      (-> focus :arguments :object (obj/updated-object content)))
                 (and (= (:name focus) "fixation")
                      (-> focus :arguments :segment)
                      (g/find-first #(obj/same-region? focus % content)
                                    vstm)))
         focus-object (cond
                        (-> obj :arguments :tracked?)
                        obj

                        (= (:name focus) "scan")
                        focus)

         focus-region (when focus-object (obj/get-region focus-object content))

         ;; the closest segment to the focus NOT already in VSTM
         closest (when focus-object
                   (some->> content
                            (g/find-first #(= (:name %) "image-segmentation"))
                            :arguments :segments
                            (filter #(not-any? (partial at-loc %)
                                               (d/filter-elements
                                                content :name "object-location")))
                            (sort-by #(reg/distance (:region %) focus-region))
                            first))]
     (when (and focus-object closest
                (some-> focus-object :arguments :threat-change neg?)
                (<= (reg/distance (:region closest) focus-region)
                    (:max-dist component)))
       (reset! (:buffer component) (make-fixation closest focus-object component)))))

  (deliver-result
   [component]
   #{@(:buffer component)}))

(defmethod print-method ProximityHighlighter [comp ^java.io.Writer w]
  (.write w (format "ProximityHighlighter{}")))

(defn start
  [& {:as args}]
  (->ProximityHighlighter (atom nil)
                          (:max-dist (merge-parameters args))))
