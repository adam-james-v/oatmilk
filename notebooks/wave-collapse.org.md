# 🌊 Wave Function Collapse

## Concepts

The wave function collapse algorithm is pretty awesome, and is well
documented online. I heard about it first from Martin Donald\'s
excellent video *[Superpositions, Sudoku, the Wave Function Collapse
algorithm.](https://www.youtube.com/watch?v=2SuvO4Gi7uY)*

This is my first attempt at an implementation for the WFC algorithm, so
I don\'t expect things to be perfect, but I have enough understanding to
give it a real go. Here are the basic concepts that matter:

1.  **Modules:** A module is a *renderable* thing. In this example, a
    module is some neat looking SVG element, but if you search around
    you\'ll probably encounter examples where each module is a pixel-art
    *tile* for a 2D game, or even a 3D mesh. Each module will have rules
    about which modules can be adjacent to it. There are several ways in
    which these rules can be defined and enforced, but the general term
    to describe them is *constraints*. I will be using sockets to define
    valid adjacencies.

2.  **Sockets:** A socket is a way to mark up a module\'s connection
    points to help determine the valid neighbours for that module. In my
    implementation, I will use integers to indicate the socket type. A
    single line at the center of an edge is a `1` socket, an edge facing
    an \'exterior\' is `0`, and an \'interior\' is `2`. It\'s important
    for my implementation to not get too caught up in specific
    meaningfulness of each socket number, because I think the socket
    system can be auotomated much more in a follow-on implementation.
    It\'s also possible to add more socket types, for example, if I had
    a set of modules with two lines at an edge, I would define a new
    socket number.

3.  **Grid and Cells:** The grid is an `[n-cols n-rows]` table of cells,
    each of which houses the superpositions of modules. It is this grid
    that gets run through the WFC algorithm and eventually is fully
    collapsed into a renderable result. A collapsed grid is just a grid
    where every cell has no more superposition.

4.  **Entropy, Superposition, Collapse:** A superposition of modules is
    representable by a list of all valid modules for a given cell, and a
    cell\'s entropy is some measure indicating the \'level of
    superposition\', roughly speaking. A higher entropy indicates more
    uncertainty about the state of the cell. In my simple
    implmementation, I calculate entropy of a cell as the count of the
    possible modules for that cell. So, the lowest possible entropy
    should be 1, which corresponds to a fully collapsed cell. A
    completely collapsed grid is when each cell has minimum entropy.

5.  **Propagation:** The goal for a given grid is to fully collapse it.
    But if a grid starts with maximum entropy in each cell, there has to
    be some way to start the process of collapse, and to calculate the
    consequences of a cell\'s reduction in entropy. This is what
    *propagation* does. Given some low entropy cell, or a random cell
    from a set of equally low entropy cells, it may be possible to
    reduce the entropy of its neighbouring cells by comparing the
    possible sockets in the cell to the existing sockets in the
    superpositions of each neighbour, and filtering out all modules that
    can no longer connect. Then, each neighbour might be able to lower
    the entropies of *their* neighbours, and so on until no entropy
    changes can occur for that iteration of the propagation. To start
    another iteration, a new low-entropy cell is chosen, and is fully
    collapsed by randomly selecting one module from its superposition of
    possible modules. Propagation continues in this way until the grid
    is fully collapsed.

**NOTE:** There are sets of modules/sockets that can cause this
algorithm to fail. My first implementation is a bit naive and doesn\'t
fully consider every failure mode. I suspect I\'ll need to build a more
robust socket/constraint system in general, but for this note, I\'m
happy with how things are working.

With some of the high level stuff under control, I\'m ready to build
something in Clojure!

## ns

As is common with my notes and experiments, I want to use my SVG
library. I\'ll also be using `clojure.set` to help with filtering
modules based on their sockets.

``` {.clojure tangle="../src/oatmilk/wave_collapse.clj"}
(ns oatmilk.wave-collapse
  (:require [clojure.set :as set]
            [clojure.string :as str]
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

## Tile Functions

Let\'s design a set of tiles that we can use to build our modules. A
tile will be a function that returns some tileable SVG element. The
function will take 0 arguments and either always return the same
element, or some random variation of the elements. I\'m also going to
define a few top-level vars to control some aspects of each tile like
the styling and size. I\'m going to restrict tiles to being square, too.

I\'d love to have variable dimension cells and modules, but that\'s
beyond the scope of this note.

``` {.clojure tangle="../src/oatmilk/wave_collapse.clj"}
(def tile-size 9)

(defn tile-style
  [el]
  (-> el
      (tf/style {:fill "none"
                 :stroke "slategray"
                 :stroke-width 1})))

(def base-tile
  (-> (el/rect tile-size tile-size)
      (tf/style {:fill "none"})))
```

The first useful tile I\'ll create is a corner. I want to have a `1`
socket at the east and south edges, so I\'ll make sure my polyline(s)
start and end at the middle points of those edges.

``` {.clojure tangle="../src/oatmilk/wave_collapse.clj"}
(defn corner
  []
  (let [shape (rand-nth [(el/polyline [[(* tile-size 0.5) (* tile-size 1.0)]
                                       [(* tile-size 0.5) (* tile-size 0.5)]
                                       [(* tile-size 1.0) (* tile-size 0.5)]])
                         (el/polyline [[(* tile-size 0.5) (* tile-size 1.0)]
                                       [(* tile-size 1.0) (* tile-size 0.5)]])
                         (path/bezier [(* tile-size 0.5) (* tile-size 1.0)]
                                      [(* tile-size 0.5) (* tile-size 0.5)]
                                      [(* tile-size 1.0) (* tile-size 0.5)])])]
    (el/g
      base-tile
      (-> shape
          (tf/translate [(* tile-size -0.5) (* tile-size -0.5)])
          tile-style))))
```

Here I bind `shape` to be a randomly chosen SVG element. For the corner,
I\'ll make a squared corner, a diagonal, and a bezier path. Each of
these shapes shows up in the list once, so there\'s a 1 in 3 chance that
any corner tile is a bezier corner. If I wanted to change the
probabilities, I could code in some notion of weights (probably good for
extensibility), or I can acheive the same result by duplicating specific
shapes in the list.

The duplication approach will work acceptably, I think, for small
amounts of duplication. But if you really wanted drastically different
weights between 2 choices, you would have to create really long lists,
and I don\'t think that\'s really the best way to go if you want to
scale things up.

A proper weights approach is a good exercise for another notebook, I
think.

The same `rand-nth` shape approach is used for the side and inner tile
functions.

``` {.clojure tangle="../src/oatmilk/wave_collapse.clj"}
(defn side
  []
  (let [shape (rand-nth [(el/polyline [[(* tile-size 0.0) (* tile-size 0.5)]
                                       [(* tile-size 1.0) (* tile-size 0.5)]])
                         (path/bezier [(* tile-size 0.0) (* tile-size 0.5)]
                                      [(* tile-size 0.5) (* tile-size 0.125)]
                                      [(* tile-size 1.0) (* tile-size 0.5)])])]
    (el/g
      base-tile
      (-> shape
          (tf/translate [(* tile-size -0.5) (* tile-size -0.5)])
          tile-style))))

(defn inner
  []
  (el/g
    base-tile
    (-> (el/circle (* tile-size 0.375 (rand)))
        tile-style
        (tf/style {:opacity (rand)}))
    (-> (el/circle (* tile-size 0.375 (rand)))
        tile-style
        (tf/style {:opacity (rand)}))))

(defn render-tiles
  [tiles]
  (let [n-tiles (count tiles)
        ny (int (Math/sqrt n-tiles))
        nx (Math/ceil (/ n-tiles ny))
        grid (p/rect-grid nx ny tile-size tile-size)]
    (lo/distribute-on-pts (map #(%) tiles) grid)))
```

``` clojure
(clerk/html (svg (render-tiles [corner side inner])))
```

## Modules

Modules are implemented as a map with two keys: `[:sockets :tile]`,
where the sockets are always a vector of 4 sockets indicating the socket
type at the north, east, south, and west edge of the module. The tile
key holds the function that returns the tile graphic.

Since I know that my tiles are square, I can create a really simple
rotation function to generate additional tiles from my basic tile
functions. I can optionally ask for 2 or 4 rotations, where I\'ll get a
set of 0 and 180 degree versions or 0, 90, 180, 270 degree versions
respectively.

I have to make sure to change both the tile function and the sockets
appropriately. If I rotate 90 degrees counter clockwise, the east facing
socket now becomes the north facing socket. And I have to create a new
tile function that wraps the original tile function in an appropriate
rotation.

``` {.clojure tangle="../src/oatmilk/wave_collapse.clj"}
(defn rotate-module
  "Rotates a tile 90 degrees counter clockwise"
  [{:keys [sockets tile]}]
  {:sockets (vec (take 4 (drop 1 (cycle sockets))))
   :tile (fn [] (tf/rotate (tile) -90))})

(defn module-rotations
  [module n]
  (when (#{2 4} n)
    (let [[b c d a] (take 4 (iterate rotate-module module))]
      (case n
        2 [a c]
        4 [a b c d]
        nil))))
```

With the rotation function working, I can define a module set.

In all cases, the `:sockets` key will hold a vector with 4 integers
pointing `[N E S W]`. These will be used to build up sets of sockets for
filtering valid modules later.

``` {.clojure tangle="../src/oatmilk/wave_collapse.clj"}
;; sockets follow [:north :east :south :west]
(def basic-module-set
  (concat
    (module-rotations {:sockets [0 1 1 0] :tile corner} 4)
    (module-rotations {:sockets [0 1 0 1] :tile side} 4)
    [{:sockets [0 0 0 0] :tile inner}]))

(def empty-module-set
  (concat
    (module-rotations {:sockets [0 1 1 0] :tile []} 4)
    (module-rotations {:sockets [0 1 0 1] :tile []} 4)
    [{:sockets [0 0 0 0] :tile []}]))

```

## Grid

The grid I\'ll represent with a map whose keys are `[col row]` tuples.
The values for each cell in the grid are the superpositions: lists of
possible modules for that cell.

Right away I\'ll make some helpers. The `neighbour-keys` function gives
me a vector of gridmap keys in the same `[N E S W]` order. This function
does not care if you give a corner or edge cell; it will return keys
that don\'t actually exist inside the gridmap. It turns out this is ok,
because when we use these keys inside the propagation function, we
ignore `nils`.

The `initial-grid` function will create a new gridmap with `ncols` and
`nrows`, and populate each cell with a superposition of every module in
the `module-set`. The resulting gridmap is one of maximum entropy, and
is ready for an initial collapse and propagation!

But before we can propagate things, we need a way to build up filters
for valid sockets.

``` {.clojure tangle="../src/oatmilk/wave_collapse.clj"}
(defn neighbour-keys
  [[col row]]
  [[col (dec row)]
   [(inc col) row]
   [col (inc row)]
   [(dec col) row]])

(defn initial-grid
  [ncols nrows module-set]
  (zipmap (p/rect-grid ncols nrows 1 1) (repeat (vec module-set))))
```

Here things may get a bit confusing, at least they did when I first
wrote this. `valid-sockets-for-module` is a function that runs on a
*single* module. This means that there will be only 1 list of 4 sockets
to work with.

We want to know, for the module we passed in, what other lists of
sockets are valid for each position. Let\'s consider just the north
direction socket to understand this function. If `n` is 1, I want any
module whose `:sockets` list has 1 in the `s` position. So, `[0 0 1 1]`
is a valid socket list for the North neighbour module to have.

Using this approach, sets are built up for each direction. Then, Since
this has to be done for each module in a *superposition*, we have to
union all valid socket list sets per direction, for each module, which
`merge-socket-sets` does.

Finally, these functions are used inside `valid-sockets-for-neighbours`,
which gives exactly the right sets for each direction from the current
position.

``` {.clojure tangle="../src/oatmilk/wave_collapse.clj"}
(defn valid-sockets-for-module
  [{:keys [sockets]} module-set]
  (let [[n e s w] sockets
        all-sockets (set (map :sockets module-set))]
    [(set (filter #(= n (nth % 2)) all-sockets))
     (set (filter #(= e (nth % 3)) all-sockets))
     (set (filter #(= s (nth % 0)) all-sockets))
     (set (filter #(= w (nth % 1)) all-sockets))]))

(defn merge-socket-sets
  [socket-sets]
  (let [[ns es ss ws] (map (fn [n] (map #(get % n) socket-sets)) [0 1 2 3])]
    (mapv #(apply set/union %) [ns es ss ws])))

(defn valid-sockets-for-neighbours
  [pos gridmap module-set]
  (merge-socket-sets (map #(valid-sockets-for-module % module-set) (get gridmap pos))))
```

I didn\'t create the perfect system, so I have a check in
`lowest-entropy-pos` for the failure mode of having a cell with no valid
tiles. This shouldn\'t really happen, but I think it becomes possible
when the given modules don\'t allow for all possible permutations of
sockets, causing a propagation to filter away the last remaining module
in a cell.

Once again, I have good work to do in another notebook 🙂.

Otherwise, if things are working properly, we can select the lowest
entropy position either as the first collapsed cell, any cell if
they\'re all the same entropy, the highest entropy cell if it\'s the
only remaining un-collapsed cell, or pick the lowest possible entropy
except for 1. This last case is the one we should see fairly often as
things propagate.

``` {.clojure tangle="../src/oatmilk/wave_collapse.clj"}
(defn lowest-entropy-pos
  [gridmap]
  (let [entropies (group-by second (update-vals gridmap count))
        [le he] (apply (juxt min max) (keys entropies))
        choices (cond
                  ;; all cells have the same entropy
                  (= 1 (count entropies))
                  (first (vals entropies))

                  ;; only one cell left un-collapsed
                  (and (= 2 (count entropies)) (= 1 (count (get entropies he))))
                  (get entropies he)

                  ;; cells either have full entropy or minimum possible entropy
                  (and (= 2 (count entropies)) (= 1 (count (get entropies 1))))
                  (get entropies he)

                  ;; some cells have greater than minimum possible entropy, pick those
                  :else (get entropies (apply min (keys (dissoc entropies 1 0)))))
        [k _] (rand-nth choices)]
    (if (< le 1)
      (println (str "Entropy Too low -> Propagation Error perhaps? Entropy: " le))
      k)))
```

## Collapse

We\'re now at the heart of it all: *collapse!* (How dramatic).

The cycle basically works as follows:

-   Collapse a Cell, either the lowest entropy one, or a random one if
    there\'s a tie
-   from that cell, propagate the consequences out using
    `update-neighbours`
-   for each changed neighbour, propagate the consequences
-   when no more neighbours have changed, you\'re done one propagation
    cycle, baby!

This implementation is very much imperative and not functional. It\'s
also probably not super efficient. It\'s a kind of \'worst of both
worlds\' situation. This is a definite area I want to improve the code
in. If there\'s a reader with a clear mind and willingness to comment on
this, please [*@-me*]{.spurious-link target="twitter.com/RustyVermeer/"}
on Twitter, or leave an issue or pull request on
[Github](https://github.com/adam-james-v/oatmilk/blob/main/notebooks/wave-collapse.org).

In the meantime, just know that `propagate` works through the above 4
point cycle and generally gets the results I want. Yay 🙌.

``` {.clojure tangle="../src/oatmilk/wave_collapse.clj"}
(defn collapse-one-at
  [pos gridmap]
  (update gridmap pos #(vector (rand-nth %))))

(defn update-neighbours
  [pos gridmap module-set]
  (let [neighbours (neighbour-keys pos)
        socket-sets (zipmap neighbours (valid-sockets-for-neighbours pos gridmap module-set))
        new-neighbours (for [k neighbours]
                         (when-let [modules (get gridmap k)]
                           (let [valid-modules (filter #((get socket-sets k) (:sockets %)) modules)]
                             [k valid-modules])))]
    (into {} new-neighbours)))

(defn propagate
  [gridmap module-set]
  (let [seed (lowest-entropy-pos gridmap)]
    (loop [gm (collapse-one-at seed gridmap)
           stack [seed]]
      (if (< (count stack) 1)
        gm
        (let [[pos stack] ((juxt peek pop) stack)
              nks (neighbour-keys pos)
              old-neighbours (into {} (mapv #(vector % (get gm %)) nks))
              new-neighbours (update-neighbours pos gm module-set)
              new-stack (into stack (comp
                                      (filter #(not= (count (get new-neighbours %))
                                                     (count (get old-neighbours %))))
                                      (map conj)) nks)]
          (recur (merge gm new-neighbours) new-stack))))))

(defn collapsed?
  [gridmap]
  (let [counts (set (map count (vals gridmap)))]
    (or (= #{1} counts)
        (= #{0 1} counts))))

(defn collapse
  [gridmap module-set]
  (if (collapsed? gridmap)
    gridmap
    (let [gridmap (-> gridmap
                      (propagate module-set))]
      (recur (propagate gridmap module-set) module-set))))
```

To make things a bit easier to use, I\'ll make a `generate` function
that lets you give the number of columns and rows and a module set to
generate with.

``` {.clojure tangle="../src/oatmilk/wave_collapse.clj"}
(defn generate
  [ncols nrows module-set]
  (collapse (initial-grid ncols nrows module-set) module-set))
```

## Rendering

Rendering works well using my `svg-clj` library. Since each tile
function emits SVG elements as hiccup data, I can render in Clerk no
problem! I also have a `svg-clj.tools/cider-show` function which
generates `./_tmp.svg` in the project directory and displays it in your
cider REPL buffer. It\'s pretty useful!

Each cell at render time should have a list containing just one tile
function. Take the first item from this list, run the function, and
translate it to the appropriate spot. Since the tile-size is known and
the cell\'s position is its key, finding this translation is just a bit
of multiplication.

``` {.clojure tangle="../src/oatmilk/wave_collapse.clj"}
(defn render-sockets
  [[n e s w]]
  (let [cols {0 "red"
              1 "green"
              2 "blue"}]
    (-> (el/g
          (-> (el/circle 2)
              (tf/translate [(* tile-size 0.45) (* tile-size 0.05)])
              (tf/style {:fill (get cols n)}))
          (-> (el/circle 2)
              (tf/translate [(* tile-size 0.95) (* tile-size 0.45)])
              (tf/style {:fill (get cols e)}))
          (-> (el/circle 2)
              (tf/translate [(* tile-size 0.45) (* tile-size 0.95)])
              (tf/style {:fill (get cols s)}))
          (-> (el/circle 2)
              (tf/translate [(* tile-size 0.05) (* tile-size 0.45)])
              (tf/style {:fill (get cols w)})))
        (tf/translate [(* tile-size -0.5) (* tile-size -0.5)]))))

(defn render-gridmap
  [gridmap]
  (let [rfn (fn [[pos modules]]
              (let [{:keys [tile]} (first modules)]
                (-> (tile)
                    #_(el/g (render-sockets sockets))
                    (tf/translate (utils/v* [tile-size tile-size] pos)))))]
    (el/g (pmap rfn gridmap))))
```

``` clojure
(def result (svg (render-gridmap (generate 64 64 basic-module-set))))
#_(tools/cider-show result)
```

``` clojure
(clerk/html result)
```

## Further Explorations

There\'s a lot for me to improve in terms of implementation, which I
find exciting. But even if things were perfect, there\'s so much
potential with this approach for creating cool stuff! I\'m not going to
end this note with a well-written essay style conclusion, I just want to
seed some ideas for you (and myself in the future):

-   do this with 3D modules!! -\> isometric projection and layering of
    SVG elements could look super cool
-   create an implementation that calculates sockets automatically, so
    that it\'s really quick and easy to just create tiles without having
    to think as much
-   try Clerk\'s interactivity stuff so I can change tiles in the
    browser and see results live
-   animate the propagation steps
-   seed the edges of the gridmap with nicer looking end tiles
-   create more organic tiling shapes
-   non-uniform cells
-   non-square cells (hexagons, triangles, blobs??)
