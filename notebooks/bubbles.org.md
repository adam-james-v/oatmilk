# ・✣・ Bubbles ・✣・

Let\'s use [Nextjournal\'s Clerk](https://github.com/nextjournal/clerk)
to build a nice literate document. I want to make an SVG drawing that I
can plot with my AxiDraw, and I want it to have a bit of randomness
while still maintaining a visual \'theme\'.

My idea is to take a set of polygons defining a letter and randomly
scatter some dots inside the boundary defined by the set of polygons.
Then, each dot is the growth center of a circle. At random, a point is
selected and \'grown\' until its radius bumps into something else,
either another circle or the boundary of the polygon.

Given these rules, I should then have a randomly filled polygon with
bubbles, which I can then start to style to my liking, perhaps by
creating different fill patterns or stroke patterns.

## ns

Let\'s pull in what we need. We always need to create a namespace and
require our libraries. It\'s generally best to use at least a 2 segment
namespace, and make the name as meaningful as possible. For a project
like this, I can keep things simple. I\'ll use my overarching project
name for these notes, *Oatmilk*, and the notebook\'s name.

It\'s good practice to only ever require namespaces that you actually
reference within your code.

``` clojure
(ns oatmilk.bubbles
  (:require [clojure.string :as str]
            [svg-clj.algorithms :refer [clip-ears]]
            [svg-clj.composites :refer [svg]]
            [svg-clj.elements :as el]
            [svg-clj.layout :as lo]
            [svg-clj.path :as path]
            [svg-clj.parametric :as p]
            [svg-clj.tools :as tools]
            [svg-clj.transforms :as tf]
            [svg-clj.utils :as utils]
            [nextjournal.clerk :as clerk]))

```

## Utils

As I write projects like this, I often discover little bits of repeating
code which end up being nice to have captured as small utility
functions. I like to put them near the top of a document because
they\'re often used later on in the code, so if I tangle the source, the
.clj file is more likely to work right away.

Note that you\'re reading this finished document and all of the utility
functions needed are present here, but it\'s important to recognize that
this section started empty and was just filled up as I went. I didn\'t
know ahead of time exactly what code I needed to write, even if the
document might accidentally imply that I did. That\'s just not how
writing code (or prose, for that matter) works.

``` clojure
(defn rpt
  "Create a random 2D point within a window from [0 0] to [s s] or [w h]. Coordinate values are integers."
  ([s] (rpt s s))
  ([w h] [(rand-int w) (rand-int h)]))

(defn show-pt
  "Creates an SVG element for rendering a 2d pt."
  [pt]
  (when pt
    (-> (el/circle 3.5)
        (tf/translate pt)
        (tf/style {:fill "hotpink"}))))

(defn simple-path-string->pts
  "Turn a simple SVG path string into a list of points. Simple means: absolute values, and straight segments."
  [s]
  (mapcat path/cmd->pts (path/path-str->cmds s)))

```

## Line-Line Intersection

To begin, we\'ll need some way to check that a circle has grown to its
maximum size. The first circle will only need to check if it\'s hit the
polygon boundary.

We can first think of how a line and a circle interact. But even before
that, I\'d like to visually make sure that my
`svg-clj.utils/line-intersection` function is working.

As written, this function will show `nil` if there are no intersections,
or will show the first point if the lines are parallel (not really
correct, but not going to break things here).

The lines are segments, but this intersection function assumes that both
lines extend infinitely, so we will always get a point unless the lines
are parallel, and the point may be outside of both line segments. This
too should be fine for our purposes here.

``` clojure
(defn line-line-intersection
  []
  (let [[la1 la2 :as la] [[0 0] [400 200]]
        [lb1 lb2 :as lb] [[0 200] (rpt 400 200)]]
    (el/g
      (-> (el/line la1 la2)
          (tf/style {:fill "none"
                     :stroke "limegreen"
                     :stroke-width 2}))
      (-> (el/line lb1 lb2)
          (tf/style {:fill "none"
                     :stroke "green"
                     :stroke-width 2}))
      (let [x (utils/line-intersection la lb)]
        (show-pt x)))))

^:nextjournal.clerk/no-cache
(clerk/html (svg (line-line-intersection)))
```

The function above will draw two green lines and a red circle where the
calculated intersection lies. It\'s a quick visual confirmation that the
intersection calculation is working, at least well enough to use in this
little project. Now let\'s work with some actual circles.

## Line-Circle Tangency

Since I want to fill up a polygon with bubbles (circles), I\'ll need to
make sure I can draw circles that end up *tangent* to line segments.
This can be done with some good old-fashioned math.

To draw a circle that has a center at some point `pt`, and is tangent to
some line `la` with start and end points `la1` and `la2`, we just need
to know what radius to draw with. This radius will be equal to the
*perpendicular* from `pt` to `la`, which is the shortest possible
straight line distance from the point to the line.

Luckily for me, I\'ve got a few utility functions from `svg-clj.utils`
that I can use.

``` clojure
(defn line-circle-tangency
  []
  (let [pt (rpt 400 200)
        [la1 la2 :as la] [[0 200] [400 0]]
        perp (utils/normalize (utils/perpendicular (utils/v- la2 la1)))
        x (utils/line-intersection la [(utils/v+ pt perp) pt])
        r (utils/distance x pt)]
  (el/g
    (-> (el/line la1 la2)
        (tf/style {:fill "none"
                   :stroke "limegreen"
                   :stroke-width 2}))
    (-> (el/circle r)
        (tf/translate pt)
        (tf/style {:fill "none"
                   :stroke "skyblue"
                   :stroke-width 2}))
    (show-pt x))))

^:nextjournal.clerk/no-cache
(clerk/html (svg (line-circle-tangency)))
```

The function `utils/perpendicular` will give the vector perpendicular to
the input vector. It\'s subtle, but these are *vectors* not *points*,
which means they have a tail pinned to the orgin `[0 0]`. In practice,
this means that to get the right vectors and distances, I have to
subtract `la2` by `la1` to get the vector parallel to `la`, then find
its perpendicular, `perp`.

Once I have this `perp` vector, I can add it to our circle\'s center
point `pt` and find the intersection point `x` between
`(utils/v+ pt perp)` and `la`.

Finally, knowing `x` and `pt`, I can use `utils/distance` to calculate
the circle\'s radius. Everything else in the function is used to build
up the image and display a green line, a blue circle, and a red
intersection.

## Circle-Circle Tangency

Circle-circle tangency will be important for creating all of the bubbles
that only ever touch other bubbles, which will be quite common along the
inner sections of the polygon.

Drawing two circles that only touch at a single point is actually easier
than drawing a circle touching a line. If you know the center points of
two circles, and the radius of the first, you can determine the radius
of the second circle by subtracting `ar` from the total distance `d`.

``` clojure
(defn circle-circle-tangency
  []
  (let [apt [200 100]
        ar 32
        bpt (rpt 200)
        d (utils/distance apt bpt)
        br (- d ar)
        x ((p/line bpt apt) (/ br d))]
  (el/g
    (-> (el/circle ar)
        (tf/translate apt)
        (tf/style {:fill "none"
                   :stroke "skyblue"
                   :stroke-width 2}))
    (when (> br 0)
      (-> (el/circle br)
          (tf/translate bpt)
          (tf/style {:fill "none"
                     :stroke "skyblue"
                     :stroke-width 2})))
    (show-pt x))))

^:nextjournal.clerk/no-cache
(clerk/html (svg (circle-circle-tangency)))
```

I\'m also doing something a bit weird looking to get the intersection
point `x` here. Using `svg-clj.parametric` (one of my favourite
namespaces to play around with), I created a parametric function
representing a line between `bpt` and `apt`. The parametric function is
a function that takes a parameter `t` which can have any value between
and including 0 to 1, and outputs a point in space. In this case, it\'s
a 2D point along the straight line curve.

You can create more complicated parametric curves and even use 3D points
(technically n-dimensional points are possible, though not necessarily
well covered within my svg library yet). There\'s a `p/bezier` curve
function in there, which I really like.

Just note that this intersection point isn\'t actually used at all when
it comes to drawing the second circle, I\'m just drawing it in for
visual reference.

## Circle Circles Tangency

It\'s nice to be able to draw a pair of circles tangent to one another,
but now I\'d like to draw many circles. At this point, I want to build
up a list of circles where none of the circles intersect at more than
one place, but always sit tangent to another circle.

To do this, I like to think of a function that will succeed in adding
just one circle to a list of circles. First, I\'ll consider what happens
if the list contains no circle at all. In this scenario, I want to
\'seed\' the whole process with a circle. Since I can\'t derive its
radius as I did above, I\'ll have to set it some other way.

I like the idea of having both a max and min radius that any circle can
take, so I\'ll pass these in as parameters along with the width and
height of the \'bubble-able\' area.

With a max and min radius, I can conj the first circle onto the list,
with a random center point and a random radius within the min and max
values.

Then, if the list of circles contains one or more entries
(`(seq circles)` does not return `nil`), All I have to do is pick a
random point and draw a circle tangent to the nearest circle. I do this
naively by calculating all of the distances between every other circle
and my new point. This is naive because it\'ll slow way down when I
start doing this for large amounts of circles. So, for this demo I just
won\'t go higher than... say 500 circles. Non-naive approaches to this
sort of thing will be a future exploration of mine.

If the calculated radius is less than the minimum radius or greater than
the max, no new circle is added and the list is returned as it was. This
is why my function is called `maybe-add-circle`, and it\'s another naive
element of my approach. As more circles get added, the liklihood of
randomly picking a point that will work decreases, as more space is
taken up by circles already. Think of throwing 10,000 darts onto a
regular-sized dartboard; as you go, you\'ll notice it\'s increasingly
difficult to avoid hitting other darts.

Anyway, that\'s fine enough for this function to work. But, I\'ve so far
only described adding a single circle to a list... how will this
actually add many circles?

I could refactor this into a recursive function (which I may do when
building my final implementation), but here I\'ll use clojure\'s
`iterate` function, which runs a function, then runs it again with the
previous run\'s output as the next run\'s input.

Since my function will take a list of circles and return a list of
circles, I can use iterate to build up my list. All I have to do is come
up with stopping criteria, because iterate will run infinitely.

My stopping criteria is simple: stop and give me the last list of
circles once the count reaches `n` circles. This is all handled with the
`bubbles` function, which also lets me set the width and height of the
bubble-able area, and the max and min radius of the bubbles.

``` clojure
(defn maybe-add-circle
  [w h max-r min-r circles]
  (let [circle-style {:fill "none"
                      :stroke "skyblue"
                      :stroke-width 2}]
    (if (seq circles)
      (let [pt (rpt w h)
            r (->> circles
                   (map (fn [[_ {:keys [cx cy r]}]]
                          (- (utils/distance pt [cx cy]) r)))
                   (apply min))]
        (if (> max-r r min-r)
          (conj circles (-> (el/circle r)
                            (tf/translate pt)
                            (tf/style circle-style)))
          circles))
      (conj circles (-> (el/circle (+ min-r (rand-int (- max-r min-r))))
                        (tf/translate (rpt w h))
                        (tf/style circle-style))))))

(defn bubbles
  [{:keys [n w h max-r min-r]}]
  (el/g (last (take-while #(< (count %) (inc n)) (iterate (partial maybe-add-circle w h max-r min-r) [])))))

(clerk/html (svg (bubbles {:n 200
                           :w 500 :h 300
                           :max-r 35 :min-r 3})))
```

## Putting it All Together

Let\'s now put these ideas together to build a polygon bubbler function.

``` clojure
(defn in-tris?
  [pt tris]
  (let [not-in (take-while #(not (utils/pt-inside? % pt)) tris)]
    (< (count not-in) (count tris))))

(defn line-circle-distance
  [[a b] pt]
  (let [perp (utils/normalize (utils/perpendicular (utils/v- b a)))
        x (utils/line-intersection [a b] [(utils/v+ pt perp) pt])
        r (utils/distance x pt)]
    r))

(defn maybe-add-circle-to-polygon
  [pts tris max-r min-r circles]
  (let [lines (partition 2 1 pts)
        [w h] (utils/bb-dims (utils/bounds-of-pts pts))
        circle-style {:fill "none"
                      :stroke "skyblue"
                      :stroke-width 2}
        pt (rpt w h)]
    (if (in-tris? pt tris)
      (if (seq circles)
        (let [r1 (->> circles
                      (map (fn [[_ {:keys [cx cy r]}]]
                             (- (utils/distance pt [cx cy]) r)))
                      (apply min))
              closest-line (first (sort-by #(line-circle-distance % pt) lines))
              r2 (line-circle-distance closest-line pt)
              r (min r1 r2)]
          (if (> max-r r min-r)
            (conj circles (-> (el/circle r)
                              (tf/translate pt)
                              (tf/style circle-style)))
            circles))
        (conj circles (-> (el/circle (+ min-r (rand-int (- max-r min-r))))
                          (tf/translate (rpt w h))
                          (tf/style circle-style))))
      circles)))

(defn polygon-bubbles
  [pts {:keys [n min-r max-r]}]
  (let [ifn (partial maybe-add-circle-to-polygon pts (:tris (clip-ears pts)) max-r min-r)]
    (el/g
      (-> (el/polygon pts)
          (tf/style {:fill "none" :stroke "red"}))
      (el/g (last (take-while #(< (count %) (inc n)) (iterate ifn [])))))))

```

## Making Something Nice

That\'s all fine and good, but I\'d like to try making something a bit
cooler with this now.

I\'ve used another tool to grab the path string of a Letter \'C\' with a
nice looking font, and created a simple utility function to convert the
path string into a set of points, which will let us draw it as a
polygon.

``` clojure
^{:nextjournal.clerk/visibility :fold}
(def c-path-str
  "M 132.29149,124.33832 V 135.82001 V 147.3017 V 158.78339 V 170.26509 H 131.61939 H 130.94729 H 130.27519 H 129.60309 L 126.80268,166.87659 L 124.00227,163.48809 L 121.20186,160.09959 L 118.40144,156.71109 L 114.43186,160.21161 L 110.02821,163.32007 L 105.1905,166.03647 L 99.918723,168.36081 L 94.359902,170.17408 L 88.549046,171.46927 L 82.486152,172.24638 L 76.171222,172.50542 L 65.151599,171.81232 L 54.832078,169.73301 L 45.212661,166.2675 L 36.293347,161.41579 L 28.193153,155.29688 L 21.031099,148.14183 L 14.807182,139.95062 L 9.5214033,130.72327 L 5.3557894,120.63478 L 2.3803511,109.86019 L 0.59508803,98.399501 L 3.9333333e-7,86.252714 L 0.59508803,74.105925 L 2.3803511,62.645236 L 5.3557894,51.870649 L 9.5214033,41.782163 L 14.807182,32.561804 L 21.031099,24.391601 L 28.193153,17.271552 L 36.293347,11.201658 L 45.212661,6.3009358 L 54.832078,2.80042 L 65.151598,0.70011049 L 76.171222,7.3242188e-6 L 81.702037,0.25204444 L 87.092831,1.0081558 L 92.343604,2.2683415 L 97.454357,4.0326014 L 102.33408,6.174917 L 106.89175,8.6812863 L 111.12737,11.551709 L 115.04095,14.786186 L 118.1214,11.649724 L 121.20185,8.5132617 L 124.28231,5.3767996 L 127.36276,2.2403374 H 128.03486 H 128.70696 H 129.37906 H 130.05116 V 13.722029 V 25.20372 V 36.685411 V 48.167103 H 129.29505 H 128.53893 H 127.78282 H 127.02671 L 126.39662,41.712152 L 124.95441,35.677264 L 122.70007,30.062437 L 119.63362,24.867671 L 115.90907,20.162978 L 111.68045,16.018367 L 106.94775,12.433839 L 101.71098,9.4093935 L 96.138161,6.9590325 L 90.397316,5.2087747 L 84.488447,4.15862 L 78.411552,3.8085684 L 71.361513,4.2496334 L 64.885559,5.5728283 L 58.98369,7.7781532 L 53.655905,10.865608 L 48.860199,14.695172 L 44.554564,19.126825 L 40.739002,24.160566 L 37.413512,29.796397 L 34.54309,35.908298 L 32.092729,42.370249 L 30.062429,49.182253 L 28.452192,56.344308 L 27.276019,63.716394 L 26.435895,71.158491 L 25.931821,78.670597 L 25.763796,86.252714 L 25.931821,93.834832 L 26.435895,101.34694 L 27.276019,108.78903 L 28.452192,116.16112 L 30.062429,123.32318 L 32.092729,130.13518 L 34.543089,136.59713 L 37.413512,142.70903 L 40.739002,148.34486 L 44.554564,153.3786 L 48.860199,157.81025 L 53.655905,161.63982 L 58.98369,164.72727 L 64.885559,166.9326 L 71.361513,168.25579 L 78.411552,168.69686 L 85.685623,168.3188 L 92.497627,167.18464 L 98.847563,165.29436 L 104.73543,162.64797 L 110.08422,159.37849 L 114.81692,155.61894 L 118.93353,151.36931 L 122.43404,146.62961 L 125.29746,141.45584 L 127.39077,136.01604 L 128.71396,130.3102 L 129.26704,124.33832 H 130.02315 H 130.77926 H 131.53538 Z")

(def c-pts
  (->> (simple-path-string->pts c-path-str)
       (drop-last 3) ;; weird error with the last couple of points... so just drop them
       (map #(utils/v* [4 4] %))))
```

WIP -\> continue writing here

Now, we can render the points nicely:

``` clojure
#_(defn render-tris
  [pts]
  (let [tris (:tris (clip-ears pts))]
    (->> tris
         (map #(-> (el/polygon %)
                   (tf/style {:fill "none"
                              :stroke "yellow"
                              :stroke-width 2})))
         el/g)))

#_(def result (svg
              #_(debug-in-polygon c-pts)
              #_(render-tris c-pts)
              #_(-> (el/polygon c-pts)
                  (tf/style {:fill "none"
                             :stroke "lightblue"
                             :stroke-width 3}))
              (polygon-bubbles c-pts
                               {:n 10
                                :max-r 40 :min-r 4})))

#_(clerk/html result)
```

## Improving Efficiency

The original polygon bubble function is very weak. It takes far too long
and can really hang if you give it a weird (but valid) polygon. In
particular, the assumptions built into my first attempt work acceptably
for convex polygons whose major and minor axes are closely lined up with
the X and Y axes. That is, if you look at the shape, there are no
\'dents\' or whacky angles, and it looks basically vertical or
horizontal. Things that look like rectangles work well enough.

But the coolest shapes are concave, like the letter \'C\', and we need
something better.

One idea I have is to pre-generate random points and then use them to
run the same algorithm. If I can pre-generate points that are guaranteed
to only be within the polygon, I only have to run the expensive checks
once, and can then otherwise run things fairly quickly knowing with
certainty that any one point grabbed from the generated list of points
will be inside the polygon, making the rest of the calculations simpler.

Now I need to build a random points function that distributes points
\'evenly\' through the polygon. I actually tried one approach that
totally failed. I\'ll show that now:

``` clojure
(defn debug-in-polygon
  [rfn n pts]
  (let [tris (:tris (clip-ears pts))
        [w h] (utils/bb-dims (utils/bounds-of-pts pts))]
    (el/g
      (for [pt (take n (repeatedly #(rfn tris)))]
        (-> (el/circle 3)
            (tf/translate pt)
            (tf/style {:fill (if (in-tris? pt tris) "green" "red")})))
      (-> (el/polygon pts)
          (tf/style {:fill "none" :stroke "red"})))))

(defn rpt-in-tris
  [tris]
  (let [[a b c] (shuffle (rand-nth tris))]
    ((p/line a b) (+ 0.05 (rand 0.9)))))

(clerk/html
  (svg (debug-in-polygon rpt-in-tris 2000 c-pts)))

(defn rpt-in-tris2
  [tris]
  (let [[w h] (utils/bb-dims (utils/bounds-of-pts (apply concat tris)))
        pt (rpt w h)]
    (if (in-tris? pt tris)
      pt
      (recur tris))))

(clerk/html
  (svg (debug-in-polygon rpt-in-tris2 2000 c-pts)))
```

So, let\'s build a better polygon-bubble function that uses a \'cached\'
list of randomly generated points, and hopefully see a speedup.

``` clojure
(defonce random-c-pts
  (let [tris (:tris (clip-ears c-pts))]
    (vec (take 2000 (repeatedly #(rpt-in-tris2 tris))))))

(defn maybe-add-circle-to-polygon2
  [pts tris max-r min-r circles]
  (let [lines (partition 2 1 pts)
        [w h] (utils/bb-dims (utils/bounds-of-pts pts))
        circle-style {:fill "none"
                      :stroke "skyblue"
                      :stroke-width 2}
        pt (rand-nth random-c-pts)]
    (if (seq circles)
      (let [r1 (->> circles
                    (map (fn [[_ {:keys [cx cy r]}]]
                           (- (utils/distance pt [cx cy]) r)))
                    (apply min))
            closest-line (first (sort-by #(line-circle-distance % pt) lines))
            r2 (line-circle-distance closest-line pt)
            r (min r1 r2)]
        (if (> max-r r min-r)
          (conj circles (-> (el/circle r)
                            (tf/translate pt)
                            (tf/style circle-style)))
          circles))
      (conj circles (-> (el/circle (+ min-r (rand-int (- max-r min-r))))
                        (tf/translate (rpt w h))
                        (tf/style circle-style))))))

(defn polygon-bubbles2
  [pts {:keys [n min-r max-r]}]
  (let [ifn (partial maybe-add-circle-to-polygon2 pts (:tris (clip-ears pts)) max-r min-r)]
    (el/g
      (-> (el/polygon pts)
          (tf/style {:fill "none" :stroke "red"}))
      (el/g (last (take-while #(< (count %) (inc n)) (iterate ifn [])))))))

```

``` clojure
(defn maybe-add-circle-to-polygon3
  [pts tris max-r min-r circles]
  (let [lines (partition 2 1 pts)
        [w h] (utils/bb-dims (utils/bounds-of-pts pts))
        circle-style {:fill "none"
                      :stroke "skyblue"
                      :stroke-width 2}
        pt (rand-nth random-c-pts)]
    (if (seq circles)
      (let [r1 (->> circles
                    (map (fn [[_ {:keys [cx cy r]}]]
                           (- (utils/distance pt [cx cy]) r)))
                    (apply min))
            closest-line (first (sort-by #(line-circle-distance % pt) lines))
            r2 (line-circle-distance closest-line pt)
            r (min r1 r2)]
        (if (> max-r r min-r)
          (conj circles (-> (el/circle r)
                            (tf/translate pt)
                            (tf/style circle-style)))
          circles))
      (conj circles (-> (el/circle (+ min-r (rand-int (- max-r min-r))))
                        (tf/translate (rpt w h))
                        (tf/style circle-style))))))

```
