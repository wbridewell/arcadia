(ns arcadia.models.iab-mack
  (:require [arcadia.architecture.registry :refer [get-sensor]]
            [arcadia.utility.display :refer [i#]]
            [clojure.java.io]
            [arcadia.models.core]
            [arcadia.vision.regions :as reg]
            (arcadia.utility [model :as model]
                             [objects :as obj])))

;; A separate model for the Mack and Rock style of inattentional blindness stimuli.
;; This will help identify the aspects related to their form of stimulus presentation
;; and will help with the construction of a task-specific attentional strategy.

;; identify the target as the big thing. this prevents length comparisons on
;; the fixation point or the critical stimulus and is task specific.
(defn- right-size? [vno]
  (>
    (reg/area (-> vno
                :arguments
                :object
                :arguments
                :region))
    200))

;; if the inhibition is inclusive of the specified point, then
;; the candidate is inhibited if it contains that point.
(defn- inhibits? [inhibitor candidate locations]
  (let [region (obj/get-region candidate locations)
        contained? (and region
                       (reg/intersect? region (-> inhibitor :arguments :region)))]
    (if (= (-> inhibitor :arguments :mode) "include")
      contained?
      (not contained?))))

;; notice that if everything is inhibited, you still look somewhere.
(defn- pick-candidate [candidates inhibitions locations]
  (if (empty? inhibitions)
    (when (seq candidates) (rand-nth candidates))
    (let [uninhibited (filter (fn [c]
                                (if (seq inhibitions)
                                  (not-any? #(inhibits? % c locations) inhibitions)
                                  true)) candidates)]
      (println "UNINHIBITED:" (count uninhibited))
      (if (and (empty? uninhibited)
               (seq (filter #(= (-> % :arguments :reason) "task") candidates)))
        ;; look for a task-specific fixation (i.e., center foveator)
        (first (filter #(= (-> % :arguments :reason) "task") candidates))

        ;; this commented out line picks a random element and is hyper unrealistic.
        (when (seq uninhibited)
          (rand-nth uninhibited))))))

(defn select-focus [expected]
  (let [new-objects (filter #(and (= (:name %) "object")
                                  (= (:type %) "instance")
                                  (= (:world %) nil)) expected)
        locations (filter #(and (= (:name %) "object-location")
                                (= (:world %) "vstm")) expected)]
    (cond
     ;; single trial.
     ;; task specific. only focus on a comparison if it's for a new object that
     ;; has the characteristics of the target for the task. ;
     (and (some #(and (= (:name %) "comparison") (= (:world %) nil)) expected)
          (some #(= (:name %) "visual-new-object") expected)
          (right-size? (first (filter #(= (:name %) "visual-new-object") expected))))

     (rand-nth (filter #(and (= (:name %) "comparison") (= (:world %) nil)) expected))


     (some #(= (:type %) "action") expected)
     (rand-nth (filter #(= (:type %) "action") expected))

     (seq new-objects)
     (rand-nth new-objects)

     (some #(and (= (:name %) "fixation") (nil? (:world %))) expected)
     (let [candidates (filter #(and (= (:name %) "fixation") (nil? (:world %))) expected)
           inhibitions (filter #(and (= (:name %) "fixation-inhibition") (nil? (:world %))) expected)
           selection (pick-candidate candidates inhibitions locations)]
       (if (nil? selection)
         (rand-nth (remove #(and (= (:name %) "fixation") (nil? (:world %))) expected))
         (do
           (println "FIXATION REGION:" (-> selection :arguments :segment :region))
           selection)))

     (seq expected)
     (rand-nth (seq expected)))))

#_{:clj-kondo/ignore [:unresolved-symbol]}
(defn sensor-setup []
  (model/setup
   (model/add stable-viewpoint)))

#_{:clj-kondo/ignore [:unresolved-symbol]}
(defn component-setup []
  (model/setup
   (model/add image-segmenter {:sensor (get-sensor :stable-viewpoint)})
   (model/add object-file-binder)
   (model/add object-locator {:sensor (get-sensor :stable-viewpoint)})
   (model/add vstm)
   (model/add display.objects)
   (model/add display.fixations
              {:sensor (get-sensor :stable-viewpoint) :x 520 :y 20})
   (model/add display
              {:panels [[(i# (:image %))]]
               :element-type :image
               :image-width 300
               :image-height 300
               :sensor (get-sensor :stable-viewpoint)
               :display-name "Video" :x 0 :y 530})
   (model/add highlighter.center {:sensor (get-sensor :stable-viewpoint)})
   (model/add comparison-recorder)
   (model/add covert-return-inhibitor)
    ;(model/add fixation-inhibitor {:sensor (get-sensor :stable-viewpoint)})
   (model/add length-comparator)
   (model/add highlighter.saliency {:map-sensor (get-sensor :stable-viewpoint)
                                    :segment-sensor (get-sensor :stable-viewpoint)})
   (model/add bottom-up-saliency {:sensor (get-sensor :stable-viewpoint)})
   (model/add highlighter.vstm)
   (model/add reporter.object-height)
   (model/add reporter.object-width)
   (model/add working-memory)))

(defn example-run
  ([]
   (example-run 40))
  ([cycles]
   (example-run "test-files/iab-mack-cogsci.m4v" cycles))
  ([video cycles]
   ;; use resolve to avoid circular dependencies for the example run
   ((resolve 'arcadia.core/startup)
    'arcadia.models.iab-mack
    'arcadia.simulator.environment.video-player
    {:video-path (.getPath (clojure.java.io/resource video))}
    cycles)))
