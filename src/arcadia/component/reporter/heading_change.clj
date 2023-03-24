(ns arcadia.component.reporter.heading-change
  "Send out an interlingua element whenever a change in heading/direction
  is detected by an object in VSTM

  Focus Responsive
    No.

  Default Behavior
    Searches VSTM for moving objects which have changed direction

  Produces
    * event, with the :event-name \"heading-change\", and
        :objects which have changed heading."
  (:require [arcadia.component.core :refer [Component]]
            [arcadia.utility [general :as g] [objects :as obj]]))

(def ^:private dir-change-threshold (/ Math/PI 12))

(defn- get-motion-obj [m]
  (-> m :arguments :objects first))

(defn- updated-motion [m motions content]
  (g/find-first #(= (obj/updated-object (get-motion-obj m) content)
                    (obj/updated-object (get-motion-obj %) content))
                motions))

(defn- heading-changed?
  "Returns true if the object has changed cardinal direction, or if the
   average angle of heading has changed over dir-change-threshold."
  [old new]
  ;; the motion's cardinal direction has changed
  (or (and (:x-dir (:arguments old))
           (:x-dir (:arguments new))
           (not= (:x-dir (:arguments old))
                 (:x-dir (:arguments new))))
      (and (:y-dir (:arguments old))
           (:y-dir (:arguments new))
           (not= (:y-dir (:arguments old))
                 (:y-dir (:arguments new))))
      ;; the direction change is above threshold
      (and (:dir (:arguments old))
           (:dir (:arguments new))
           (> (obj/direction-difference (:dir (:arguments old))
                                        (:dir (:arguments new)))
              dir-change-threshold))))

(defn heading-change-event [obj]
  {:name "event"
   :arguments {:event-name "heading-change"
               :objects (list obj)}
   :type "instance"
   :world nil})

(defrecord HeadingChangeReporter [buffer motion-history]
  Component
  (receive-focus
    [component focus content]
    (reset! (:buffer component) nil)
    (let [motions (filter #(and (= (:name %) "event-stream")
                                (= (:event-name (:arguments %)) "motion"))
                          content)]
      (doall (map #(when-let [new (updated-motion % motions content)]
                     (when (heading-changed? % new)
                       (println "------------------------------------------CHANGE IN HEADING-------------------------"
                                (-> new get-motion-obj :arguments :color)
                                (-> % :arguments ((juxt :x-dir :y-dir :dir))) (-> new :arguments ((juxt :x-dir :y-dir :dir))))
                       (swap! (:buffer component) conj
                              (heading-change-event (get-motion-obj new)))))
                  @(:motion-history component)))

     ;; update the history of motion events for the next cycle
      (reset! (:motion-history component) motions)))

  (deliver-result
    [component]
    @buffer))

(defmethod print-method HeadingChangeReporter [comp ^java.io.Writer w]
  (.write w (format "HeadingChangeReporter{}")))

(defn start []
  (->HeadingChangeReporter (atom nil) (atom nil)))
