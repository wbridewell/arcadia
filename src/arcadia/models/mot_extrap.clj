
(ns arcadia.models.mot-extrap
  (:require [arcadia.architecture.registry :refer [get-sensor]]
            (arcadia.utility [attention-strategy :as att]
                             [display :refer [i#]]
                             [descriptors :as d]
                             [evaluation :as evaluation]
                             [general :as g]
                             [image :as img]
                             [model :as model]
                             [objects :as obj]
                             [opencv :as cv])
            [arcadia.vision.regions :as reg]
            clojure.java.io
            arcadia.models.core))

;; For this task, we want to allow objects to double or half in
;; size when two circles intersect or stop intersecting.  Let's
;; actually make it 1.5, which means one object is equal to the
;; other * 2.5.
(def ^:dynamic *mot-size-similarity-threshold* 1.5)

;; And here's the size similarity comparison
(defn- size-comparator [obj1 obj2]
  (obj/similar-size-contours? obj1 obj2
                              :threshold  *mot-size-similarity-threshold*))

(def ^:private lut
  "look-up table for colorizing images before displaying" (img/make-colormap-RYBG))

(defn- image-format-fn
  "Formats images before displaying them. Specially converts 32F images such that
   instead of ranging from -1 to 1, they range from 0 to 255."
  [src]
  (if (and (cv/mat-type src)
           (= (cv/depth src) cv/CV_32F))
    (-> src (cv/add 1.0) (cv/convert-to cv/CV_8U :alpha 127.49)
        (cv/cvt-color cv/COLOR_GRAY2BGR) (cv/apply-lookup-table! lut))
    src))

(defn- corresponding-crowding-fixation
  "Returns a highlighter.crowding fixation to the same location as the
  highlighter.maintenance fixation."
  [focus content]
  (when (d/element-matches? focus :name "fixation" :reason "maintenance")
    (first (filter #(obj/same-region? % focus content)
                   (d/filter-elements content :name "fixation" :reason "crowding")))))

(def ^:private debug-information
  "Information that will be shown in the display.status"
  (list ["Threat"
         (i# (or (-> % :focus :arguments :threat-distance)
                 (-> % :focus (corresponding-crowding-fixation (:content %))
                     :arguments :threat-distance)))]

        ["Crowded"
         (i# (if (d/element-matches? (:focus %) :name "object")
               (-> % :focus :arguments :crowded?)
               (-> % :focus (corresponding-crowding-fixation (:content %))
                   :arguments :crowded?)))]
        ["Colliding"
         (i# (let [content (:content %)]
               (cond
                 (d/some-element content :reason "post-collision")
                 "post-collision"
                 (d/some-element content :reason "collision")
                 true
                 (d/some-element content :reason "maintenance")
                 false)))]
        ["Extrapolating"
         (i# (let [content (:content %)]
               (cond
                 (d/some-element content :reason "maintenance" :expected-region some?)
                 true
                 (d/some-element content :reason "maintenance")
                 false)))]
        ["Next Threat"
         (i# (-> % :content (d/filter-elements :name "fixation" :reason "crowding")
                 (->> (filter (fn [f] (not (obj/same-region? f (:focus %) (:content %)))))
                      (sort-by (fn [f] (-> f :arguments :threat-distance)) >))
                 first :arguments :threat-distance))]))

(defn- detect-collision
  "Model-specific function for determining if an object has undergone a collision.
  old and new the object's region before and after the possible collision.
  Trajectory-data is additional information generated by the highlighter.maintenance.
  This function uses changes in size to detect collisions."
  [old new trajectory-data]
  (let [old-area (reg/area old)
        new-area (reg/area new)]
    (and (> new-area old-area)
         (or (not (obj/similar-size-areas? old-area new-area))
             (and (:min-area trajectory-data)
                  (not (obj/similar-size-areas?
                        new-area (:min-area trajectory-data))))))))

(defn- detect-collision-end
  "Model-specific function for determining if an object has finished a collision.
  old and new the object's region before and after the possible collision.
  Trajectory-data is additional information generated by the highlighter.maintenance.
  This function uses changes in size to detect collisions."
  [old new trajectory-data]
  (let [old-area (reg/area old)
        new-area (reg/area new)]
    (and (< new-area old-area)
         (not (obj/similar-size-areas? old-area new-area))
         (:min-area trajectory-data)
         (obj/similar-size-areas?
          new-area (:min-area trajectory-data)))))

;; Looks for a crowding fixation that is being actively maintained
;; OR
;; If a maintenance fixation says the target in question is undergoing a collision
;; event, stay on that.
(defn- ongoing-maintenance-fixation [maintenance-fixation collision-fixation most-crowded
                                     crowding-fixations content]
  (and maintenance-fixation
       (or collision-fixation
;;            (-> maintenance-fixation :arguments :calculating-delta-queue?)
           (obj/same-region? maintenance-fixation most-crowded content)
           (g/find-first #(obj/same-region? % maintenance-fixation content)
                          crowding-fixations))
       maintenance-fixation))

;; Always allow other crowded regions to interrupt maintenance fixations.
#_{:clj-kondo/ignore [:unused-private-var]}
(defn- ongoing-maintenance-fixation-or-interruption
  [maintenance-fixation collision-fixation most-crowded
   crowding-fixations content]
  (or (and maintenance-fixation (not collision-fixation)
           (g/find-first #(not (obj/same-region? % maintenance-fixation content))
                          crowding-fixations))
      (and maintenance-fixation
           (or collision-fixation
               ;;            (-> maintenance-fixation :arguments :calculating-delta-queue?)
               (obj/same-region? maintenance-fixation most-crowded content)
               (g/find-first #(obj/same-region? % maintenance-fixation content)
                              crowding-fixations))
           maintenance-fixation)))


(defn select-focus [expected]
  (let [fixations (d/filter-elements expected :name "fixation")
        crowding-fixations (d/filter-elements fixations :reason "crowding")]
    (or (d/first-element expected :name "gaze" :saccading? true)
        (d/first-element expected :type "action")
        (d/first-element expected :name "object" :type "instance" :world nil)
        (d/first-element expected :name "saccade")

        ;; fixations
        (d/rand-element (att/remove-object-fixations fixations expected) :reason "color")
        (d/first-element fixations :reason "gaze")
        (d/rand-element fixations :reason "color")

        (ongoing-maintenance-fixation
          (d/first-element fixations :reason "maintenance")
          (or (d/first-element fixations :reason "collision")
              (d/first-element fixations :reason "post-collision"))
          (d/first-element crowding-fixations :reason "crowding" :most-crowded? true)
          (d/filter-elements crowding-fixations :reason "crowding" :crowded? true)
          expected)

        (d/first-element crowding-fixations :reason "crowding" :most-crowded? true)
        (d/rand-element fixations :reason "memory")
        (g/rand-if-any fixations)
        ;; end fixations

        (g/rand-if-any (seq expected)))))

#_{:clj-kondo/ignore [:unresolved-symbol]}
(defn sensor-setup []
  (model/setup
   (model/add stable-viewpoint)))

#_{:clj-kondo/ignore [:unresolved-symbol]}
(defn component-setup []
  (model/setup
   (model/add image-segmenter {:sensor (get-sensor :stable-viewpoint)})
   (model/add object-file-binder)
   (model/add button-pusher)
   (model/add vstm {:diff-size-comparator size-comparator})
   (model/add object-locator {:sensor (get-sensor :stable-viewpoint)
                              :use-hats? true})

   (model/add saccade-requester {:sensor (get-sensor :stable-viewpoint)})
   (model/add smooth-pursuit {:sensor (get-sensor :stable-viewpoint)})
   (model/add highlighter.gaze-target)
   (model/add highlighter.maintenance {:detect-collision detect-collision
                                       :detect-collision-end detect-collision-end
                                       :min-delta-queue-count 2})
   (model/add display.fixations
              {:x 0 :y 20 :display-name "Visual Processing"
               :sensor (get-sensor :stable-viewpoint)
               :image-format-fn image-format-fn
               :panels
               [["Object Location Map"
                (i# (-> % :content (d/first-element :name "object-location-map")
                        :arguments :data))
                :elements [[(i# (:panel %))]]
                :copy-image? false :initial-glyphs nil]
                ["Segments" nil
                 :glyphs
                 [[(i# (-> % :content (d/filter-elements :name "fixation"
                                                       :reason "crowding")))
                   :glyph-value (i# (when (or (-> % :glyph :arguments :crowded?)
                                              (-> % :glyph :arguments :most-crowded?))
                                      (-> % :glyph (obj/get-region (:content %)))))
                   :color java.awt.Color/green
                   :line-width (i# (if (-> % :glyph :arguments :most-crowded?)
                                     2.0 1.0))]
                  [(i# (-> % :content (d/first-element :name "fixation"
                                                       :reason "collision")
                            :arguments :expected-region))
                    :color java.awt.Color/blue :fill-color java.awt.Color/blue
                    :alpha 0.8 :shape-scale 0.4 :line-width 1.0]]]]})
   (model/add display.status
              {:x 1050 :y 170 :caption-width 100
               :elements
               debug-information})
   (model/add display.controls {:x 1050 :y 450})
   ; (model/add display.scratchpad {:x 0 :y 0 :monitor 0
   ;                              :panel-width 800 :panel-height 600
   ;                              :sensor (get-sensor :stable-viewpoint)})
   (model/add display.environment-messages {:x 1050 :y 20})
   (model/add highlighter.color
              {:sensor (get-sensor :stable-viewpoint)
               :excluded-colors #{"black" "gray"}})
   (model/add highlighter.crowding)
   (model/add new-object-guess-recorder {:color "blue"})))

(defn example-run
  ([]
   (example-run nil false))
  ([max-cycles]
   (example-run max-cycles false))
  ([max-cycles record?]
   ;; use resolve to avoid circular dependencies for the example run
   ((resolve 'arcadia.core/startup)
    'arcadia.models.mot-extrap 'arcadia.simulator.environment.stimulus-player
    {:file-path (.getPath (clojure.java.io/resource
                            "test-files/MOT_Extrap_2Targets.mp4"))
     :viewing-width 15.375
     :max-actions 2}
    max-cycles
    :record? record?)))

(defn regression-test []
  (let [results
        ((resolve 'arcadia.core/startup)
         'arcadia.models.mot-extrap
         'arcadia.simulator.environment.stimulus-player
         {:file-path (.getPath (clojure.java.io/resource
                                "test-files/MOT_Extrap_2Targets.mp4"))
          :viewing-width 15.375
          :max-actions 2 :max-cycles 250}
         nil
         :output-map
         {:guesses (evaluation/final-content d/filter-elements :name "new-object-guess")
          :prediction (evaluation/first-focus d/element-matches? :name "fixation"
                                              :reason "maintenance" :expected-region some?)})]
    (cond
      (nil? (:prediction results))
      [false "Failed to predict a target's location during an occlusion event."]

      (< (count (:guesses results)) 2)
      [false "Failed to make two guesses about whether the highlighted objects are the tracked targets."]

      (= 1 (count (d/filter-elements (:guesses results) :old? true)))
      [false "Only recognized one of the two tracked targets."]

      (d/not-any-element (:guesses results) :old? true)
      [false "Recognized neither of the two tracked targets."]

      :else
      [true ""])))
