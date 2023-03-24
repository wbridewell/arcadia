(ns arcadia.component.highlighter.crowding
  "Covert visual attention is drawn to tracked objects that are close to other
  objects in the visual field. Thoughts are that this helps resolve object
  identity when there is a risk of confusion with other items in the visual
  scene. This component requests fixations to tracked objects that are
  currently crowded by looking at each object's distance to the proto-object
  nearest to it.

  Focus Responsive
    * fixation
    * object

  If there are multiple objects that are crowded at roughly the same distance,
  prefer fixating on the one that was recently in the focus of attention.

  Default Behavior
  Calculates the distance between each tracked object's center and its nearest
  other proto-object (segment), sort these by distance, and return one or more
  crowded objects. Typically all tracked objects are returned with an
  indicator of which ones are crowded, and which one is most crowded.

  Produces
    * fixation
       includes a corresopnding :object, whether that object is :crowded? or
       the :most-crowded? of all the objects. Also indicates the absolute
       :threat-pixel-distance and a normalized :threat-distance to the
       nearest proto-object. The :reason for the fixation is \"crowding\"."
  (:require [arcadia.component.core :refer [Component merge-parameters]]
            [arcadia.utility.geometry :as geo]
            [arcadia.utility [general :as g] [objects :as obj]]))

(def ^:parameter crowding-dist
  "Max distance at which crowding will be detected. The unit is diameters of the threatening object."
  2.8)

(def ^:parameter strategy
  ":take-first returns the most crowded target.
   :all-below-threshold returns all crowded targets within the crowding distance.
   :all returns all targets"
  :all)

(defn- region-distance [r1 r2]
  (geo/distance (geo/center r1) (geo/center r2)))

(defn- normalize-distance
  "Normalizes the distance between an object and a crowding threat by dividing by the diameter of the second region.
  The radius is treated as the average of the region's height and width."
  [distance threat]
  (/ distance (-> threat :region geo/radius (* 2))))

(defn- make-fixation
  "Create a fixation to the object due to crowding caused by a nearby segment (threat),
  including an absolute :threat-pixel-distance and a normalized :threat-distance"
  [object threat content source]
  (when (and object threat)
    (let [abs-distance (region-distance (obj/get-region object content) (:region threat))
          norm-distance (normalize-distance abs-distance threat)]
      {:name "fixation"
       :arguments {:object object
                   ;:threat threat  ;; don't report the crowding object for now,
                                    ;; since we don't have evidence for its availability.
                   :threat-distance norm-distance
                   :threat-pixel-distance abs-distance
                   :threat-change (some->> object :arguments :threat-distance (- norm-distance) (#(compare % 0)))
                   :crowded? (< norm-distance (:crowding-dist (:parameters source)))
                   :reason "crowding"}
       :world nil
       :type "instance"})))

(defn- get-threat
  "Returns the segment in segments closest to the region r"
  [r segments]
  (first (sort-by #(region-distance r (:region %))
                  (remove #(= r (:region %)) segments))))

(defn- crowding-comp
  "Compare two fixations using their absolute :threat-pixel-distances,
  breaking ties by preferring fixations to the focus-region."
  [fixation1 fixation2 focus-region content]
  (if (g/near-equal? (-> fixation1 :arguments :threat-pixel-distance)
                     (-> fixation2 :arguments :threat-pixel-distance))
    (obj/same-region? fixation1 focus-region content)
    (compare (-> fixation1 :arguments :threat-pixel-distance)
             (-> fixation2 :arguments :threat-pixel-distance))))

(defrecord CrowdingHighlighter [buffer parameters old-segments]
  Component
  (receive-focus
    [component focus content]
    (let [fixations (some->> (obj/get-vstm-objects content :tracked? true)
                             (map #(make-fixation % (get-threat (obj/get-region % content)
                                                                @(:old-segments component))
                                                  content component))
                             (remove nil?)
                             (sort #(crowding-comp %1 %2 (obj/get-region focus content) content))
                             vec
                            ;; set the most-crowded? tag of the first item to true
                            ;; if there are any fixations left
                             (g/apply-if seq #(assoc-in % [0 :arguments :most-crowded?] true)))]
      (reset! (:buffer component)
              (condp = (:strategy parameters)
                :take-first (first fixations)
                :all-below-threshold (filter #(-> % :arguments :threat-distance
                                                  (< (:crowding-dist parameters)))
                                             fixations)
                :all fixations))
      (reset! (:old-segments component)
              (->> content (g/find-first #(= (:name %) "image-segmentation")) :arguments :segments))))

  (deliver-result
    [component]
    @buffer))

(defmethod print-method CrowdingHighlighter [comp ^java.io.Writer w]
  (.write w (format "CrowdingHighlighter{}")))

(defn start [& {:as args}]
  (->CrowdingHighlighter (atom nil)
                         (merge-parameters args)
                         (atom nil)))
