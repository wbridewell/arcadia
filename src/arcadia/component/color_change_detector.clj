(ns arcadia.component.color-change-detector
  "The ColorChangeDetector takes color changes from the color-change component,
  and creates a corresponding event signifying that the object is changing in color

  Focus Responsive
    No.

  Default Behavior
   If an object in VSTM changes color, report that color change as an event

  Produces
   * event, with a :event-name color-change, and the new object stored in :objects"
  (:require [arcadia.component.core :refer [Component merge-parameters]]
            [arcadia.utility [general :as g] [objects :as obj]]
            [arcadia.vision.regions :as reg]))

(def ^:parameter event-lifespan "the number of cycles this event lasts before decaying" 23)

(defn- color-label [obj]
  (if (-> obj :arguments :brightness)
    (str (-> obj :arguments :brightness) " " (-> obj :arguments :color))
    (-> obj :arguments :color)))

(defn- make-event
  "When obj has a color, and either the color
  or the brightness has changed, returns an event signifying that change."
  [obj color-fixation component]
  (when (and (-> obj :arguments :color)
             (-> color-fixation :arguments :color)
             (not= (color-label obj)
                   (color-label color-fixation)))
    {:name "event"
     :arguments {:event-name "color-change"
                 :old (color-label obj)
                 :new (color-label color-fixation)
                 :event-lifespan (:event-lifespan component)
                 :objects (list obj)}
     :type "instance"
     :source component
     :world nil}))

(defrecord ColorChangeDetector [buffer event-lifespan] Component
  (receive-focus
   [component focus content]
   (->> (obj/get-vstm-objects content :tracked? true)
        (map (fn [obj]
               (make-event obj
                           (g/find-first #(and (= (:name %) "fixation")
                                               (= (:reason (:arguments %)) "color")
                                               (reg/intersect? (-> obj :arguments :region)
                                                               (-> % :arguments :segment :region)))
                                         content)
                           component)))
        (remove nil?)
        (reset! (:buffer component))))
  (deliver-result
   [component]
   (set @(:buffer component))))

(defmethod print-method ColorChangeDetector [comp ^java.io.Writer w]
  (.write w (format "ColorChangeDetector{}")))

(defn start [& {:as args}]
  (->ColorChangeDetector
   (atom nil)
   (:event-lifespan (merge-parameters args))))
