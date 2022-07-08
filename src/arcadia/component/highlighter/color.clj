(ns arcadia.component.highlighter.color
  "Evidence suggests that goals can affect where a person looks based on a
  small number of visual features, including color. This component enables
  selecting the next target for covert visual attention based on a limited
  number of color cues.

  Focus Responsive
  No.

  Default Behavior
  Associates a color identifier (not semantic) with each image segment and
  requests a fixation to each one.

  Produces
   * a fixation for each segment (of the target color) in the visual field
     that includes
       a :segment that contains the candidate segment for fixation,
       a :sensor to identify the source of the request,
       a :reason specifying that the request is due to segment color, and
       a :color argument that names the color associated with the segment.

  NOTE: this component can assign color identifiers to all the segments or it
  can be tuned to return only those fixations that match a target color. this
  allows it to respond to the current goal. in general, the \"all segments\"
  approach should be avoided as it pushes the choice about which color is
  important to the strategy level. instead the target color should be
  determined by the current goals (and not, as is now the case, at)
  initialization time.

  NOTE: the behavior could, in principle, be expanded to cover more colors
  or to take lighting effects into account.

  NOTE: Values below need to be updated, now that HSV is being computed from
  32F BGR, rather than from 8UC BGR. The changes appear to be:
  (1) H covers 0-360. Red is at the top and the bottom. Blue is 2/3 of the way
      through (so around 240, but we go a bit low to catch cyan).
  (2) S ranges from 0 to 1.
  (3) V ... no idea, but don't need it."
  (:require [arcadia.component.core :refer [Component merge-parameters]]
            [arcadia.utility [general :as g] [image :as img] [opencv :as cv]]))

(def ^:parameter ^:required sensor
  "a sensor used to generate proto-object representations from visual input (required)"
  nil)

(def ^:parameter target-color "name of the target color, as a string" nil)
(def ^:parameter excluded-colors "set of colors for segments that should not be
  highlighted" #{"black"})

(def ^:parameter threshold "minimum percentage of pixels required to be labeled a color" 0.5)

(def ^:parameter color-ranges "hashmap mapping color names (strings) to low/high HSV ranges"
  img/HSV-color-ranges)

(def ^:parameter segment-type "element name for the segments used for highlighting" 
  "image-segmentation")

(defn- report-color
  "Suppresses a particular color from being reported when multiple valid descriptions detected"
  [valid-colors dispreferred-color]
    ;(println valid-colors dispreferred-color)
  (if (<= (count valid-colors) 1) (first valid-colors)
    (first (remove #(= % dispreferred-color) valid-colors))))

(defn- get-color
  "Returns a color string for the segment or nil if it is not recognized."
  [segment parameters]
  (let [mat (cv/cvt-color (:image segment) cv/COLOR_BGR2HSV)
        color-ranges (:color-ranges parameters)]
    (report-color (filter
                   #(-> (img/color-mask mat (get color-ranges %))
                        (cv/mean-value :mask (:mask segment))
                        (> (* 255 (:threshold parameters))))
                   (keys color-ranges)) "white")))

(defn- get-brightness [segment color parameters]
  (when color
    (let [brightness
          (-> segment :image (cv/convert-to cv/COLOR_BGR2HLS)
              (cv/mean-value
               :mask (-> segment :image (cv/convert-to cv/COLOR_BGR2HSV)
                         (img/color-mask (get (:color-ranges parameters) color))))
              second)]
      (cond
        (> brightness (* 256 2/3)) "light"
        (< brightness 256/3) "dark"))))

;; should be only one segmentation for a particular sensor per cycle.
(defn- get-segments
  "Get the segments for the specified sensor from accessible content."
  [content segment-type sensor]
  (:segments
   (:arguments
    (g/find-first #(and (= (:name %) segment-type)
                        (= (:sensor (:arguments %)) sensor))
                  content))))


(defn- make-fixation
  "Construct a fixation element."
  [segment sensor component]
  (when-let [color (get-color segment (:parameters component))]
    {:name "fixation"
     :arguments {:segment segment
                 :sensor sensor
                 :reason "color"
                 :color color
                 :brightness (get-brightness segment color (:parameters component))}
     :world nil
     :source component
     :type "instance"}))

(defrecord ColorHighlighter [sensor parameters buffer]
  Component
  (receive-focus
   [component focus content]
   (->> (get-segments content (:segment-type (:parameters component)) (:sensor component))
        (map #(make-fixation % sensor component))
        (filter (if (:target-color (:parameters component))
                  #(some-> % :arguments :color
                           (= (:target-color (:parameters component))))

                  #(and (-> % :arguments :color)
                        (nil? ((:excluded-colors (:parameters component))
                               (-> % :arguments :color))))))
        (reset! (:buffer component))))


  (deliver-result
   [component]
   (set @(:buffer component))))

(defmethod print-method ColorHighlighter [comp ^java.io.Writer w]
  (.write w (format "ColorHighlighter{}")))

(defn start
  [& {:as args}]
  (let [p (merge-parameters args)]
    (->ColorHighlighter (:sensor p) p (atom nil))))
