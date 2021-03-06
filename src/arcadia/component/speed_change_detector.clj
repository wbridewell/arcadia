(ns arcadia.component.speed-change-detector
  "The speed-change-detector searches objects in VSTM for changes in average
  speed, generated by the highlighter.maintenance.

  Focus-Responsive
    No.

  Default Behavior
    Construct a speed-change event whenever an object in VSTM's speed
    varies by more than the speed-change-threshold.

  Produces
    event- with the :event-name \"speed-change\", and the :objects which have changed speed."
  (:require [arcadia.utility.vectors :as vec]
            [arcadia.component.core :refer [Component merge-parameters]]
            [arcadia.utility [objects :as obj]]))

(def ^:parameter speed-change-threshold "minimum change in speed to be detected" 0.15)
(def ^:private min-delta-count 4)

(defn- speed
  "Extract the speed of an object."
  [obj]
  (-> obj :arguments ((juxt :precise-delta-x :precise-delta-y)) vec/norm))

(defn- speed-changed? [old new speed-change-threshold]
  (and (:precise-delta-x (:arguments old))
       (:precise-delta-y (:arguments old))
       (:precise-delta-x (:arguments new))
       (:precise-delta-y (:arguments new))
       (> (:precise-delta-count (:arguments old)) min-delta-count)
       (> (Math/abs (- (speed new) (speed old))) speed-change-threshold)))

(defn make-event [old new component]
  {:name "event"
   :arguments {:event-name "speed-change"
               :event-lifespan 25
               :delta-vel (- (speed new) (speed old))
               :delta-x-vel (- (-> new :arguments :precise-delta-x)
                               (-> old :arguments :precise-delta-x))
               :delta-y-vel (- (-> new :arguments :precise-delta-y)
                               (-> old :arguments :precise-delta-y))
               :speeding-up? (> (Math/abs (speed new)) (Math/abs (speed old)))
               :slowing-down? (< (Math/abs (speed new)) (Math/abs (speed old)))
               :objects (list new)}
   :type "instance"
   :source component
   :world nil})

(defrecord SpeedChangeDetector [buffer vstm-history speed-change-threshold] Component
  (receive-focus
   [component focus content]
   (reset! (:buffer component) nil)

   (doall (map #(let [new (obj/updated-object % content)]
                  (when (speed-changed? % new (:speed-change-threshold component))
                    (println "------------------------------------------CHANGE IN SPEED-------------------------"
                             (-> % :arguments ((juxt :color :precise-delta-x :precise-delta-y))) "==>"
                             (-> new :arguments ((juxt :color :precise-delta-x :precise-delta-y))))
                    (swap! (:buffer component) conj (make-event % new component))))
               @(:vstm-history component)))

   ;; update the history of VSTM for the next cycle
   (reset! (:vstm-history component)
           (obj/get-vstm-objects content :tracked? true)))

  (deliver-result
   [component]
   (set @(:buffer component))))

(defmethod print-method SpeedChangeDetector [comp ^java.io.Writer w]
  (.write w (format "SpeedChangeDetector{}")))

(defn start
  [& {:as args}]
  (->SpeedChangeDetector (atom nil) (atom [])
                         (:speed-change-threshold (merge-parameters args))))
