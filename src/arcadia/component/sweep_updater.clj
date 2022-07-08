(ns arcadia.component.sweep-updater
  "This component is part of a left-to-right horizontal sweep inhibition
  process used for explicit counting.

  Focus Responsive
    * object
    * number-report


  Default Behavior
  When focus on a new object occurs during explicit enumeration, a
    memory-update is produced to change the sweep-position to the
    most recently enumerated object.
  When a number-report is focused on, then the sweep-position is
    reset.

  Produces
  memory-update (sweep-position) - contains a location that was most
    recently counted.

  Displays
  n/a

  mode: exclude - inhibit regions that exclude the point
        include - inhibit regions that include the point"
  (:require [arcadia.utility [objects :as obj]]
            [arcadia.vision.regions :as reg]
            [arcadia.component.core :refer [Component]]))

(defn change-element [value source]
  {:name "memorize"
   :arguments {:element
               {:name "sweep-position"
                :arguments {:usage "inhibitor" :value value :update-on "change"}
                :type "instance"
                :world nil
                :source source}}
   :type "action"
   :world nil
   :source source})

(defn update-position [position old source]
  {:name "memory-update"
   :arguments {:new
               {:name "sweep-position"
                :arguments {:usage "inhibitor" :value position :update-on "change"}
                :type "instance"
                :world nil
                :source source}
               :old old}
   :type "action"
   :world nil
   :source source})

(defn track-sweep-position [position content source]
  ;; assumes that we're only keeping one change conter at a time.
  ;; could expand this so that the counter is tied to a specific object.
  (let [sweep-position (filter #(and (= (:name %) "sweep-position")
                                 (= (:world %) "working-memory")
                                 (= (:type %) "instance"))

                           content)]
    (if (empty? sweep-position)
      (change-element position source)
      (do (println "position = " position) (update-position (max position (:value (:arguments (first sweep-position)))) (first sweep-position) source)))))

(def min-area 50)
(def max-area 20000)
(defn- task-object? [obj]
  (let [seg-area (-> obj :arguments :region reg/area)]
      (and (> seg-area min-area) (< seg-area max-area))))

(defrecord SweepUpdater [buffer]
  Component
  (receive-focus
   [component focus content]
   ;; assumes all changes are task relevant
   (let [vstm (filter #(and (= (:name %) "object")
                            (= (:world %) "vstm")) content)
         vstm-regions (map #(obj/get-region % content) vstm)
         vstm-sweep-point (some->> vstm-regions (remove nil?) seq (map :x)
                                   (apply max))
         sweep-position (filter #(and (= (:name %) "sweep-position")
                                      (= (:world %) "working-memory")
                                      (= (:type %) "instance"))

                                content)
         phon-buffer (filter #(and (= (:name %) "lexeme")
                                   (= (:world %) "phonological-buffer")) content)]

     (cond
       (and (= (:name focus) "object") (task-object? focus) (seq phon-buffer))
       (let [new-sweep-position (-> focus :arguments :region reg/center)]
         (reset! (:buffer component)
                 (track-sweep-position new-sweep-position content component)))

       (and (= (:name focus) "memorize")
            (= (:name (:element (:arguments focus))) "number-report"))
       (reset! (:buffer component)
               (update-position 0.0 (first sweep-position) component))

       :else
       (reset! (:buffer component) nil))))

  (deliver-result
   [component]
   #{@(:buffer component)}))

(defmethod print-method SweepUpdater [comp ^java.io.Writer w]
  (.write w (format "SweepUpdater{}")))

(defn start []
  (SweepUpdater. (atom nil)))
