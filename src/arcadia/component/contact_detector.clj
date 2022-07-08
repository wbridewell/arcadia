(ns arcadia.component.contact-detector
  "the contact-detector detects the moment of contact between a focused
  object and other objects in VSTM.

  Focus-Responsive
   * fixation: if the focus is a fixation, detect contact between the object of
               fixation and other objects in VSTM.
   * object:   if the focus is an object, detect contact between the object
               and other objects in VSTM

  Default Behavior
    None.

  Produces
    * event, with an :event-name of \"contact\" and :objects that are in contact"
  (:require [arcadia.component.core :refer [Component merge-parameters]]
            [arcadia.vision.regions :as reg]
            [arcadia.utility [general :as g] [objects :as obj]]))

(def ^:parameter contact-threshold
  "the maximum distance (in pixels) between objects considered intersecting"
  15.0)

(defn- contact
  "Generates a contact event to be stored in episodic-memory"
  [objects distance]
  {:name "event"
   :arguments
   {:event-name "contact"
    :distance distance
    :objects objects}
   :world nil
   :type "instance"})

(defn in-contact?
  "Two objects are in contact if they have overlapping regions
   or are within a threshold distance from each other, but not exactly
   the same regions If the regions are exactly equal, the objects
   are treated as equal.

   Returns the distance between the two bounding boxes if true."
  [o1 o2 max-contact-distance content]
  (let [r1 (obj/get-estimated-region o1 content)
        r2 (obj/get-estimated-region o2 content)
        dist (reg/distance (obj/get-region o1 content) (obj/get-region o2 content))]
    (when (and (< dist max-contact-distance)
               (not= r1 r2))
      dist)))


(defrecord ContactDetector [buffer max-contact-distance]
  Component
  (receive-focus
    [component focus content]
    (let [vstm (obj/get-vstm-objects content :tracked? true)
          object (or (and (= (:name focus) "fixation")     ;; object fixation
                          (:object (:arguments focus)))
                     (and (= (:name focus) "fixation")     ;; segment fixation
                          (:segment (:arguments focus))
                          (g/find-first #(obj/same-region? focus % content) vstm))
                     (and (= (:name focus) "object")        ;; object
                          focus))
          ;; the object in focus
          ;object (if (-> fobject :arguments :tracked?)
          ;         (updated-object fobject content))

          ;; the objects in the same region as object, paired
          ;; with their distance to the object
          collided (when object
                     (mapcat #(when-let [d (in-contact? object %
                                                        (:max-contact-distance component)
                                                        content)]
                                (list [% d]))
                             vstm))]

      (if (and object (seq collided))
        (do
          (reset! (:buffer component) (map #(contact #{object (first %)} (second %)) collided))
          (doall (map #(println "----CONTACT-----------------------------------------"
                                (map (comp :color :arguments) (:objects (:arguments %)))
                                (:distance (:arguments %)))
                      @(:buffer component))))
        (reset! (:buffer component) nil))))

  (deliver-result
   [component]
   (set @(:buffer component))))

(defmethod print-method ContactDetector [comp ^java.io.Writer w]
  (.write w (format "ContactDetector{}")))

(defn start
  "Create a ContactDetector component. By default, the maximum distance
   between contacting objects is default-contact-threshold. Optionally,
   a task-specific parameter value can be given."
  [& {:as args}]
  (->ContactDetector (atom nil)
                     (:contact-threshold (merge-parameters args))))
