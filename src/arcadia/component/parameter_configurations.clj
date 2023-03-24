(ns arcadia.component.parameter-configurations
  "This file contains common parameter configurations for some components. To add a
   component with one of these configurations, call (model/add configuration-name)
   instead of (model/add component-name). You can still specify your own parameters,
   including parameters that overwrite those specified here."
  (:require (arcadia.utility [objects :as obj]
                             [display :refer [blank-canvas i# pause-button]]
                             [descriptors :as d]
                             [general :as g])
            [arcadia.utility.geometry :as geo]))


(def display.status
  "Configures the display component to display status info (the cycle number,
   focus, and source). Use :elements to display additional information."
  {:namespace 'arcadia.component.display
   :parameters
   {:display-name "Status" :panel-width 300 :caption-width 70 :precision 2
    :elements nil
    :initial-elements
    [[(i# (g/time-string)) :center? true :precision nil]
     ["Cycle" (i# (-> % :cycle))]
     ["Focus" (i# (->> % :focus)) :display-fn :name]
     ["Source" (i# (-> % :focus meta :source))]]}})

(def display.objects
  "Configures the display component to display information about objects
   in VSTM. Use :elements to display additional information about each object,
   or replace :panels to display information about something else, instead
   of VSTM objects."
  {:namespace 'arcadia.component.display
   :parameters
   {:display-name "Objects" :panel-width 200 :caption-width 80 :precision 0
    :rows 2 :cols 2 :flatten-panels? true
    :panels
    [[(i# (-> % :content (d/filter-elements :name "object" :world "vstm")
              (->> (sort-by #(:slot (:arguments %)) <))))]]
    :elements nil
    :initial-elements
    [[(i# (list (-> % :panel :arguments :mask)
                (-> % :panel :arguments :image)))
      :center? true :element-type :image :image-width 80 :image-height 80]
     ["location" (i# (some-> % :panel :arguments :region geo/center))]
     ["latest"
      (i# (some-> % :panel (obj/get-tracked-region (:content %)) geo/center))
      :italic? true :caption-italic? false]]}})

(def display.fixations
  "Configures the display component to display an image depicting segments
   and fixations. Use :glyphs to display additional things in the image."
  {:namespace 'arcadia.component.display
   :parameters
   {:display-name "Fixations" :element-type :image :image-scale 0.5 :center? true
    :elements [[(blank-canvas :black)]]
    :initial-glyphs
    [[(i# (-> % :content (d/first-element :name "image-segmentation")
              :arguments :segments))]
     [(i# (map (comp :region :arguments)
               (-> % :content (d/filter-elements :name "object-location"))))
      :line-width 1.0 :color :orange]
     [(i# (map (comp :region :arguments)
               (-> % :content (d/filter-elements :name "object" :world "vstm"
                                                 :tracked? true))))
      :line-width 2.0 :color :red]
     [(i# (when (d/element-matches? (:focus %) :name "object" :tracked? true)
            (-> % :focus :arguments :region)))
      :line-width 2.0 :color :red :shape-scale 0.5]
     [(i# (when (d/element-matches? (:focus %) :name "fixation")
            (-> % :focus (obj/get-region (:content %)))))
      :line-width 2.0 :color :orange :shape-scale 0.5]]}})

(def display.scratchpad
  "Configures the display component to display text, data, or images as
   they are added to it."
  {:namespace 'arcadia.component.display
   :parameters
   {:display-name "Debug" :panel-width 1000 :panel-height 1000
    :text-size 1.05 :element-spacing 20 :precision 2
    :pretty-data? true :browsable? true :instant-updates? true
    :flatten-elements? true :element-type nil
    :initial-elements [["Content" (i# (cond->> (:content %)
                                        (seq? (:content %)) (sort-by :name)))
                        :collapsed? true :flatten-elements? false]
                       ["Registry" (i# (:registry %)) :collapsed? true]]}})

(def display.controls
  "Configures the display component to display a pause/play/step button."
  {:namespace 'arcadia.component.display
   :parameters
   {:display-name "Control" :icon-width 60 :element-spacing 10 :indent 5
    :panel-width 250 :panel-height 70
    :refresh-every-cycle? false
    :initial-elements [[(pause-button)]]
    :elements nil}})

(def display.environment-messages
  "Configures the display component to display messages from the environment."
  {:namespace 'arcadia.component.display
   :parameters
   {:instant-updates? true :refresh-every-cycle? false :text-size 1.3 :bold? true
    :display-name "Responses" :panel-width 250 :elements nil :number-updates? true}})
