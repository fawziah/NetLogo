// (C) 2011 Uri Wilensky. https://github.com/NetLogo/NetLogo

package org.nlogo.agent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.nlogo.api.Color;
import org.nlogo.api.CompilerServices;
import org.nlogo.api.LogoException;
import org.nlogo.util.MersenneTwisterFast;
import org.nlogo.api.Nobody$;
import org.nlogo.api.Program;
import org.nlogo.api.Shape;
import org.nlogo.api.ShapeList;
import org.nlogo.api.TrailDrawerInterface;
import org.nlogo.api.WorldDimensions;
import org.nlogo.api.ImporterUser;
import org.nlogo.api.WorldDimensionException;
import org.nlogo.api.ValueConstraint;

import org.nlogo.api.AgentException;

// A note on wrapping: normally whether x and y coordinates wrap is a
// product of the topology.  But we also have the old "-nowrap" primitives
// that don't wrap regardless of what the topology is.  So that's why many
// methods like distance() and towards() take a boolean argument "wrap";
// it's true for the normal prims, false for the nowrap prims. - ST 5/24/06

public strictfp class World
    implements org.nlogo.api.World {

  public static final Double ZERO = Double.valueOf(0.0);
  public static final Double ONE = Double.valueOf(1.0);

  public final TickCounter tickCounter = new TickCounter();

  public double ticks() {
    return tickCounter.ticks();
  }

  public final Timer timer = new Timer();
  private final ShapeList _turtleShapeList;

  public ShapeList turtleShapeList() {
    return _turtleShapeList;
  }

  private final ShapeList _linkShapeList;

  public ShapeList linkShapeList() {
    return _linkShapeList;
  }

  private double patchSize = 12.0; // keep the unzoomed patchSize here
  TrailDrawerInterface trailDrawer;
  private final Map<Agent, Double> lineThicknesses = new HashMap<Agent, Double>();
  Topology topology;
  RootsTable rootsTable;
  protected Protractor _protractor;

  public Protractor protractor() {
    return _protractor;
  }

  public LinkManager linkManager;
  public TieManager tieManager;

  public InRadiusOrCone inRadiusOrCone;

  // This is a flag that the engine checks in its tightest innermost loops
  // to see if maybe it should stop running NetLogo code for a moment
  // and do something like halt or update the display.  It doesn't
  // particularly make sense to keep it in World, but since the check
  // occurs in inner loops, we want to put in a place where the engine
  // can get to it very quickly.  And since every Instruction has a
  // World object in it, the engine can always get to World quickly.
  //  - ST 1/10/07
  public volatile boolean comeUpForAir = false;  // NOPMD pmd doesn't like 'volatile'

  public World() {
    _turtleShapeList = new ShapeList();
    _linkShapeList = new ShapeList();

    _observer = createObserver();
    _observers = new ArrayAgentSet(Observer.class, 1, "observers", false, this);

    linkManager = new LinkManager(this);
    tieManager = new TieManager(this, linkManager);

    inRadiusOrCone = new InRadiusOrCone(this);
    _protractor = new Protractor(this);

    _observers.add(_observer);
    changeTopology(true, true);
    // create patches in the constructor, it's necessary in case
    // the first model we load is 1x1 since when we do create patches
    // in the model loader we only do the reallocation if the dimensions
    // are different than the stored dimensions.  This doesn't come up
    // often because almost always load the default model first, and there
    // aren't many 1x1 models. ev 2/5/07
    createPatches(_minPxcor, _maxPxcor, _minPycor, _maxPycor);
  }

  Observer createObserver() {
    return new Observer(this);
  }

  /// empty agentsets

  private final AgentSet _noTurtles = new ArrayAgentSet(Turtle.class, 0, false, this);
  private final AgentSet _noPatches = new ArrayAgentSet(Patch.class, 0, false, this);
  private final AgentSet _noLinks = new ArrayAgentSet(Link.class, 0, false, this);

  public AgentSet noTurtles() {
    return _noTurtles;
  }

  public AgentSet noPatches() {
    return _noPatches;
  }

  public AgentSet noLinks() {
    return _noLinks;
  }

  ///

  public void trailDrawer(TrailDrawerInterface trailDrawer) {
    this.trailDrawer = trailDrawer;
  }

  /// get/set methods for World Topology
  Topology getTopology() {
    return topology;
  }

  public void changeTopology(boolean xWrapping, boolean yWrapping) {
    topology = Topology.getTopology(this, xWrapping, yWrapping);
    if (_patches != null) // is null during initialization
    {
      for (AgentSet.Iterator it = _patches.iterator(); it.hasNext();) {
        ((Patch) it.next()).topologyChanged();
      }
    }
  }

  public double wrappedObserverX(double x) {
    try {
      x = topology.wrapX(x - topology.followOffsetX());
    } catch (AgentException e) {
      org.nlogo.util.Exceptions.ignore(e);
    }
    return x;
  }

  public double wrappedObserverY(double y) {
    try {
      y = topology.wrapY(y - topology.followOffsetY());
    } catch (AgentException e) {
      org.nlogo.util.Exceptions.ignore(e);
    }
    return y;
  }

  public double followOffsetX() {
    return _observer.followOffsetX();
  }

  public double followOffsetY() {
    return _observer.followOffsetY();
  }

  // These are just being used for setting the checkboxes in the ViewWidget config dialog
  //   with default values. These should no be used within World to control any behavior.
  //   All wrapping related behavior specific to a topology is/should-be hardcoded in the methods
  //   for each specific topological implementation.
  public boolean wrappingAllowedInX() {
    return (topology instanceof Torus || topology instanceof VertCylinder);
  }

  public boolean wrappingAllowedInY() {
    return (topology instanceof Torus || topology instanceof HorizCylinder);
  }

  /// export world

  public void exportWorld(java.io.PrintWriter writer, boolean full) {
    new Exporter(this, writer).exportWorld(full);
  }

  public void importWorld(Importer.ErrorHandler errorHandler, ImporterUser importerUser,
                          Importer.StringReader stringReader, java.io.BufferedReader reader)
      throws java.io.IOException {
    new Importer(errorHandler, this,
        importerUser, stringReader).importWorld(reader);
  }

  // anything that affects the outcome of the model should happen on the
  // main RNG
  public final MersenneTwisterFast mainRNG = new MersenneTwisterFast();

  // anything that doesn't and can happen non-deterministically (for example monitor updates)
  // should happen on the auxillary rng. JobOwners should know which RNG they use.
  public final MersenneTwisterFast auxRNG = new MersenneTwisterFast();

  /// random seed generator

  public double generateSeed() {
    return org.nlogo.api.RandomSeedGenerator$.MODULE$.generateSeed();
  }

  /// line thickness

  public void setLineThickness(Agent agent, double size) {
    lineThicknesses.put(agent, Double.valueOf(size));
  }

  public double lineThickness(Agent agent) {
    Double size = lineThicknesses.get(agent);
    if (size != null) {
      return size.doubleValue();
    }
    return 0.0;
  }

  public void removeLineThickness(Agent agent) {
    lineThicknesses.remove(agent);
  }

  /// equality

  void drawLine(double x0, double y0, double x1, double y1,
                Object color, double size, String mode) {
    trailDrawer.drawLine(x0, y0, x1, y1, color, size, mode);
  }

  // boxed versions of geometry/size methods, for efficiency
  Double _worldWidthBoxed;

  public Double worldWidthBoxed() {
    return _worldWidthBoxed;
  }

  Double _worldHeightBoxed;

  public Double worldHeightBoxed() {
    return _worldHeightBoxed;
  }

  Double _minPxcorBoxed;

  public Double minPxcorBoxed() {
    return _minPxcorBoxed;
  }

  Double _minPycorBoxed;

  public Double minPycorBoxed() {
    return _minPycorBoxed;
  }

  Double _maxPxcorBoxed;

  public Double maxPxcorBoxed() {
    return _maxPxcorBoxed;
  }

  Double _maxPycorBoxed;

  public Double maxPycorBoxed() {
    return _maxPycorBoxed;
  }

  /// world geometry

  int _worldWidth;

  public int worldWidth() {
    return _worldWidth;
  }

  int _worldHeight;

  public int worldHeight() {
    return _worldHeight;
  }

  // arbitrary sizes
  int _minPxcor;

  public int minPxcor() {
    return _minPxcor;
  }

  int _minPycor;

  public int minPycor() {
    return _minPycor;
  }

  int _maxPxcor;

  public int maxPxcor() {
    return _maxPxcor;
  }

  int _maxPycor;

  public int maxPycor() {
    return _maxPycor;
  }

  public double wrapX(double x)
      throws AgentException {
    return topology.wrapX(x);
  }

  public double wrapY(double y)
      throws AgentException {
    return topology.wrapY(y);
  }

  public double wrap(double pos, double min, double max) {
    return Topology.wrap(pos, min, max);
  }

  public void diffuse(double param, int vn)
      throws AgentException, PatchException {
    topology.diffuse(param, vn);
  }

  public void diffuse4(double param, int vn)
      throws AgentException, PatchException {
    topology.diffuse4(param, vn);
  }

  public int roundX(double x)
      throws AgentException {
    // floor() is slow so we don't use it
    try {
      x = topology.wrapX(x);
    } catch (AgentException ex) {
      throw new AgentException("Cannot access patches beyond the limits of current world.");
    }
    if (x > 0) {
      return (int) (x + 0.5);
    } else {
      int intPart = (int) x;
      double fractPart = intPart - x;
      return (fractPart > 0.5) ? intPart - 1 : intPart;
    }
  }

  public int roundY(double y)
      throws AgentException {
    // floor() is slow so we don't use it
    try {
      y = topology.wrapY(y);
    } catch (AgentException ex) {
      throw new AgentException("Cannot access patches beyond the limits of current world.");
    }
    if (y > 0) {
      return (int) (y + 0.5);
    } else {
      int intPart = (int) y;
      double fractPart = intPart - y;
      return (fractPart > 0.5) ? intPart - 1 : intPart;
    }
  }

  public Turtle createTurtle(AgentSet breed) {
    return new Turtle(this, breed, ZERO, ZERO);
  }

  // c must be in 0-13 range
  // h can be out of range
  public Turtle createTurtle(AgentSet breed, int c, int h) {
    Turtle baby = new Turtle(this, breed, ZERO, ZERO);
    baby.colorDoubleUnchecked(Double.valueOf(5 + 10 * c));
    baby.heading(h);
    return baby;
  }

  /// observer/turtles/patches

  final AgentSet _observers;

  public AgentSet observers() {
    return _observers;
  }

  final Observer _observer;

  public Observer observer() {
    return _observer;
  }

  AgentSet _patches = null;

  public AgentSet patches() {
    return _patches;
  }

  AgentSet _turtles = null;

  public AgentSet turtles() {
    return _turtles;
  }

  AgentSet _links = null;

  public AgentSet links() {
    return _links;
  }

  public AgentSet agentClassToAgentSet(Class<? extends Agent> agentClass) {
    if (agentClass == Turtle.class) {
      return _turtles;
    } else if (agentClass == Patch.class) {
      return _patches;
    } else if (agentClass == Observer.class) {
      return _observers;
    } else if (agentClass == Link.class) {
      return _links;
    }
    throw new IllegalArgumentException
        ("agentClass = " + agentClass);
  }

  public WorldDimensions getDimensions() {
    return new WorldDimensions(_minPxcor, _maxPxcor, _minPycor, _maxPycor);
  }

  public boolean isDimensionVariable(String variableName) {
    return
        variableName.equalsIgnoreCase("MIN-PXCOR") ||
            variableName.equalsIgnoreCase("MAX-PXCOR") ||
            variableName.equalsIgnoreCase("MIN-PYCOR") ||
            variableName.equalsIgnoreCase("MAX-PYCOR") ||
            variableName.equalsIgnoreCase("WORLD-WIDTH") ||
            variableName.equalsIgnoreCase("WORLD-HEIGHT");
  }

  public WorldDimensions setDimensionVariable(String variableName, int value, WorldDimensions d)
      throws WorldDimensionException {
    if (variableName.equalsIgnoreCase("MIN-PXCOR")) {
      d.minPxcor_$eq(value);
    } else if (variableName.equalsIgnoreCase("MAX-PXCOR")) {
      d.maxPxcor_$eq(value);
    } else if (variableName.equalsIgnoreCase("MIN-PYCOR")) {
      d.minPycor_$eq(value);
    } else if (variableName.equalsIgnoreCase("MAX-PYCOR")) {
      d.maxPycor_$eq(value);
    } else if (variableName.equalsIgnoreCase("WORLD-WIDTH")) {
      d.minPxcor_$eq(growMin(_minPxcor, _maxPxcor, value, d.minPxcor()));
      d.maxPxcor_$eq(growMax(_minPxcor, _maxPxcor, value, d.maxPxcor()));
    } else if (variableName.equalsIgnoreCase("WORLD-HEIGHT")) {
      d.minPycor_$eq(growMin(_minPycor, _maxPycor, value, d.minPycor()));
      d.maxPycor_$eq(growMax(_minPycor, _maxPycor, value, d.maxPycor()));
    }
    return d;
  }

  public int growMin(int min, int max, int value, int d)
      throws WorldDimensionException {
    if (value < 1) {
      throw new WorldDimensionException();
    }

    if (max == -min) {
      if (value % 2 != 1) {
        throw new WorldDimensionException();
      }
      return -(value - 1) / 2;
    } else if (max == 0) {
      return -(value - 1);
    }

    return d;
  }

  public int growMax(int min, int max, int value, int d)
      throws WorldDimensionException {
    if (value < 1) {
      throw new WorldDimensionException();
    }

    if (max == -min) {
      if (value % 2 != 1) {
        throw new WorldDimensionException();
      }
      return (value - 1) / 2;
    } else if (min == 0) {
      return (value - 1);
    }

    return d;
  }

  public boolean equalDimensions(WorldDimensions d) {
    return d.minPxcor() == _minPxcor &&
      d.maxPxcor() == _maxPxcor &&
      d.minPycor() == _minPycor &&
      d.maxPycor() == _maxPycor;
  }

  public Patch getPatch(int id) {
    return (Patch) _patches.toArray()[id];
  }

  public Patch getPatchAt(double x, double y)
      throws AgentException {
    int xc = roundX(x);
    int yc = roundY(y);
    int id = ((_worldWidth * (_maxPycor - yc))
        + xc - _minPxcor);
    return (Patch) _patches.toArray()[id];
  }

  // this procedure is the same as calling getPatchAt when the topology is a torus
  // meaning it will override the Topology's wrapping rules and
  public Patch getPatchAtWrap(double x, double y) {
    int xc, yc, intPart;
    double fractPart;
    x = Topology.wrap(x, _minPxcor - 0.5, _maxPxcor + 0.5);
    y = Topology.wrap(y, _minPycor - 0.5, _maxPycor + 0.5);
    if (x > 0) {
      xc = (int) (x + 0.5);
    } else {
      intPart = (int) x;
      fractPart = intPart - x;
      xc = (fractPart > 0.5) ? intPart - 1 : intPart;
    }
    if (y > 0) {
      yc = (int) (y + 0.5);
    } else {
      intPart = (int) y;
      fractPart = intPart - y;
      yc = (fractPart > 0.5) ? intPart - 1 : intPart;
    }
    int patchid = ((_worldWidth * (_maxPycor - yc)) + xc - _minPxcor);
    return (Patch) _patches.toArray()[patchid];
  }

  public boolean validPatchCoordinates(int xc, int yc) {
    return
        xc >= _minPxcor &&
            xc <= _maxPxcor &&
            yc >= _minPycor &&
            yc <= _maxPycor;
  }

  public Patch fastGetPatchAt(int xc, int yc) {
    return (Patch) _patches.toArray()[(_worldWidth * (_maxPycor - yc))
        + xc - _minPxcor];
  }

  public Turtle getTurtle(long id) {
    return (Turtle) _turtles.getAgent(Double.valueOf(id));
  }

  public Link getLink(Object end1, Object end2, AgentSet breed) {
    return linkManager.findLink((Turtle) _turtles.getAgent(end1),
        (Turtle) _turtles.getAgent(end2),
        breed, false);
  }

  private long nextTurtleIndex = 0;

  void nextTurtleIndex(long nextTurtleIndex) {
    this.nextTurtleIndex = nextTurtleIndex;
  }

  long nextTurtleIndex() {
    return nextTurtleIndex;
  }

  long newTurtleId() {
    return nextTurtleIndex++;
  }

  // we assign an unique ID to links, like turtles, except that
  // it's not visible to anyone and it can't affect the outcome of
  // the model. I added it because it greatly complicates hubnet
  // view mirroring to have the only unique identifier be a
  // 3 element list. ev 5/1/08
  private long nextLinkIndex = 0;

  long newLinkId() {
    return nextLinkIndex++;
  }

  // used by Importer and Parser
  public Turtle getOrCreateTurtle(long id) {
    Turtle turtle = getTurtle(id);
    if (turtle == null) {
      turtle = new Turtle(this, id);
      nextTurtleIndex = StrictMath.max(nextTurtleIndex, id + 1);
    }
    return turtle;
  }

  public Link getOrCreateLink(Double end1, Double end2, AgentSet breed) {
    return getOrCreateLink(getOrCreateTurtle(end1.longValue()),
        getOrCreateTurtle(end2.longValue()), breed);

  }

  public Link getOrCreateLink(Turtle end1, Turtle end2, AgentSet breed) {
    Link link = getLink(end1.agentKey(), end2.agentKey(), breed);
    if (link == null) {
      link = linkManager.createLink(end1, end2, breed);
    }
    return link;
  }

  public Link getOrCreateDummyLink(Object end1, Object end2, AgentSet breed) {
    Link link = (end1 == Nobody$.MODULE$ || end2 == Nobody$.MODULE$) ? null
        : getLink(((Turtle) end1).agentKey(), ((Turtle) end2).agentKey(), breed);

    if (link == null) {
      link = new DummyLink(this, end1, end2, breed);
    }

    return link;
  }

  // possibly need another array for 3D colors
  // since it seems messy to collapse 3D array into 2D
  int[] patchColors;

  // GLView
  // this is used by the OpenGL texture code to decide whether
  // it needs to make a new texture or not - ST 2/9/05
  boolean patchColorsDirty = true;

  public boolean patchColorsDirty() {
    return patchColorsDirty;
  }

  public void markPatchColorsDirty() {
    patchColorsDirty = true;
  }

  public void markPatchColorsClean() {
    patchColorsDirty = false;
  }

  // performance optimization -- avoid drawing an all-black bitmap if we
  // could just paint one big black rectangle
  boolean patchesAllBlack = true;
  public boolean patchesAllBlack() {
    return patchesAllBlack;
  }

  // performance optimization for 3D renderer -- avoid sorting by distance
  // from observer unless we need to.  once this flag becomes true, we don't
  // work as hard as we could to return it back to false, because doing so
  // would be expensive.  we just reset it at clear-all time.
  boolean mayHavePartiallyTransparentObjects = false;
  public boolean mayHavePartiallyTransparentObjects() {
    return mayHavePartiallyTransparentObjects;
  }

  public int[] patchColors() {
    return patchColors;
  }

  int patchesWithLabels = 0; // for efficiency in Renderer

  public int patchesWithLabels() {
    return patchesWithLabels;
  }

  /// creating & clearing

  public void createPatches(WorldDimensions dim) {
    createPatches(dim.minPxcor(), dim.maxPxcor(), dim.minPycor(), dim.maxPycor());
  }

  public void createPatches(int minPxcor, int maxPxcor,
                            int minPycor, int maxPycor) {
    patchScratch = null;
    _minPxcor = minPxcor;
    _maxPxcor = maxPxcor;
    _minPycor = minPycor;
    _maxPycor = maxPycor;
    _worldWidth = maxPxcor - minPxcor + 1;
    _worldHeight = maxPycor - minPycor + 1;

    rootsTable = new RootsTable(_worldWidth, _worldHeight);

    _worldWidthBoxed = Double.valueOf(_worldWidth);
    _worldHeightBoxed = Double.valueOf(_worldHeight);
    _minPxcorBoxed = Double.valueOf(_minPxcor);
    _minPycorBoxed = Double.valueOf(_minPycor);
    _maxPxcorBoxed = Double.valueOf(_maxPxcor);
    _maxPycorBoxed = Double.valueOf(_maxPycor);

    if (_program.breeds() != null) {
      for (Iterator<Object> iter = _program.breeds().values().iterator();
           iter.hasNext();) {
        ((AgentSet) iter.next()).clear();
      }
    }
    if (_program.linkBreeds() != null) {
      for (Iterator<Object> iter = _program.linkBreeds().values().iterator();
           iter.hasNext();) {
        ((AgentSet) iter.next()).clear();
      }
    }
    _turtles = new TreeAgentSet(Turtle.class, "TURTLES", this);
    _links = new TreeAgentSet(Link.class, "LINKS", this);

    int x = minPxcor;
    int y = maxPycor;
    Agent[] patchArray = new Agent[_worldWidth * _worldHeight];
    patchColors = new int[_worldWidth * _worldHeight];
    Arrays.fill(patchColors, Color.getARGBbyPremodulatedColorNumber(0.0));
    patchColorsDirty = true;

    int numVariables = _program.patchesOwn().size();

    _observer.resetPerspective();

    for (int i = 0; _worldWidth * _worldHeight != i; i++) {
      Patch patch = new Patch(this, i, x, y, numVariables);
      x++;
      if (x == (maxPxcor + 1)) {
        x = minPxcor;
        y--;
      }
      patchArray[i] = patch;
    }
    _patches = new ArrayAgentSet(Patch.class, patchArray, "patches", this);
    patchesWithLabels = 0;
    patchesAllBlack = true;
    mayHavePartiallyTransparentObjects = false;
  }

  public void clearAll() {
    tickCounter.clear();
    clearTurtles();
    clearPatches();
    clearGlobals();
    clearLinks();
    _observer.resetPerspective();
    mayHavePartiallyTransparentObjects = false;
  }

  // in a 2D world the drawing lives in the
  // renderer so the workspace takes care of it.
  public void clearDrawing() {
  }

  public void stamp(Agent agent, boolean erase) {
    trailDrawer.stamp(agent, erase);
  }

  public double patchSize() {
    return patchSize;
  }

  public boolean patchSize(double patchSize) {
    if (this.patchSize != patchSize) {
      this.patchSize = patchSize;
      return true;
    }
    return false;
  }

  public Object getDrawing() {
    return trailDrawer.getDrawing();
  }

  public boolean sendPixels() {
    return trailDrawer.sendPixels();
  }

  public void markDrawingClean() {
    trailDrawer.sendPixels(false);
  }

  public void clearPatches() {
    for (AgentSet.Iterator iter = _patches.iterator(); iter.hasNext();) {
      Patch patch = (Patch) iter.next();
      patch.pcolorDoubleUnchecked(Color.BoxedBlack());
      patch.label("");
      patch.labelColor(Color.BoxedWhite());
      try {
        for (int j = patch.NUMBER_PREDEFINED_VARS;
             j < patch.variables.length;
             j++) {
          patch.setPatchVariable(j, ZERO);
        }
      } catch (AgentException ex) {
        throw new IllegalStateException(ex);
      }
    }
    patchesAllBlack = true;
  }

  public void clearTurtles() {
    if (_program.breeds() != null) {
      for (Iterator<Object> iter = _program.breeds().values().iterator();
           iter.hasNext();) {
        ((AgentSet) iter.next()).clear();
      }
    }
    for (AgentSet.Iterator iter = _turtles.iterator(); iter.hasNext();) {
      Turtle turtle = (Turtle) iter.next();
      lineThicknesses.remove(turtle);
      linkManager.cleanup(turtle);
      turtle.id(-1);
    }
    _turtles.clear();
    for (AgentSet.Iterator iter = _patches.iterator(); iter.hasNext();) {
      ((Patch) iter.next()).clearTurtles();
    }
    nextTurtleIndex = 0;
    _observer.updatePosition();
  }

  public void clearLinks() {
    if (_program.linkBreeds() != null) {
      for (Iterator<Object> iter = _program.linkBreeds().values().iterator();
           iter.hasNext();) {
        AgentSet set = ((AgentSet) iter.next());
        set.clear();
      }
    }
    for (AgentSet.Iterator iter = _links.iterator(); iter.hasNext();) {
      Link link = (Link) iter.next();
      link.id = -1;
    }
    _links.clear();
    nextLinkIndex = 0;
    linkManager.reset();
  }

  public void clearGlobals() {
    for (int j = _program.interfaceGlobals().size();
         j < _observer.variables.length;
         j++) {
      try {
        ValueConstraint con = _observer.variableConstraint(j);
        if (con != null) {
          _observer.setObserverVariable(j, con.defaultValue());
        } else {
          _observer.setObserverVariable(j, ZERO);
        }
      } catch (AgentException ex) {
        throw new IllegalStateException(ex);
      } catch (LogoException ex) {
        throw new IllegalStateException(ex);
      }
    }
  }

  // this exists to support recompiling a model without causing
  // agent state information to be lost.  it is called after a
  // successful recompilation.
  public void realloc() {
    // copy the breed agentsets from the old Program object from
    // the previous compile to the new Program object that was
    // created when we recompiled.  any new breeds that were created,
    // we create new agentsets for.  (if this is a first compile, all
    // the breeds will be created.)  any breeds that no longer exist
    // are dropped.
    for (String breedName : _program.breeds().keySet()) {
      AgentSet breed = (AgentSet) oldBreeds.get(breedName);
      if (breed == null) {
        _program.breeds().put
            (breedName,
                new TreeAgentSet(Turtle.class, breedName.toUpperCase(), this));
      } else {
        _program.breeds().put(breedName, breed);
      }
    }
    for (Iterator<String> breedNames = _program.linkBreeds().keySet().iterator();
         breedNames.hasNext();) {
      String breedName = breedNames.next();
      boolean directed = _program.linkBreeds().get(breedName).equals("DIRECTED-LINK-BREED");
      AgentSet breed = (AgentSet) oldLinkBreeds.get(breedName);
      if (breed == null) {
        breed = new TreeAgentSet(Link.class, breedName.toUpperCase(), this);
      } else {
        // clear the lists first
        breed.clearDirected();
      }
      _program.linkBreeds().put(breedName, breed);
      breed.setDirected(directed);
    }
    List<Agent> doomedAgents = new ArrayList<Agent>();
    // call Agent.realloc() on all the turtles
    try {
      if (_turtles != null) {
        for (AgentSet.Iterator iter = _turtles.iterator(); iter.hasNext();) {
          Agent agt = iter.next().realloc(true);
          if (agt != null) {
            doomedAgents.add(agt);
          }
        }
        for (Iterator<Agent> i = doomedAgents.iterator(); i.hasNext();) {
          ((Turtle) i.next()).die();
        }
        doomedAgents.clear();
      }
    } catch (AgentException ex) {
      throw new IllegalStateException(ex);
    }
    // call Agent.realloc() on all links
    try {
      if (_links != null) {
        for (AgentSet.Iterator iter = _links.iterator(); iter.hasNext();) {
          Agent agt = iter.next().realloc(true);
          if (agt != null) {
            doomedAgents.add(agt);
          }
        }
        for (Iterator<Agent> i = doomedAgents.iterator(); i.hasNext();) {
          ((Link) i.next()).die();
        }
        doomedAgents.clear();
      }
    } catch (AgentException ex) {
      throw new IllegalStateException(ex);
    }
    // call Agent.realloc() on all the patches
    try {
      // Note: we only need to realloc() if the patch variables have changed.
      //  ~Forrest ( 5/2/2007)
      if (_patches != null && !_program.patchesOwn().equals(oldPatchesOwn)) {
        for (AgentSet.Iterator iter = _patches.iterator(); iter.hasNext();) {
          iter.next().realloc(true);
        }
      }
    } catch (AgentException ex) {
      throw new IllegalStateException(ex);
    }
    // call Agent.realloc() on the observer
    _observer.realloc(true);
    // and finally...
    turtleBreedShapes.setUpBreedShapes(false, _program.breeds());
    linkBreedShapes.setUpBreedShapes(false, _program.linkBreeds());
  }

  /// patch scratch
  //  a scratch area that can be used by commands such as _diffuse

  double[][] patchScratch;

  public double[][] getPatchScratch() {
    if (patchScratch == null) {
      patchScratch = new double[_worldWidth][_worldHeight];
    }
    return patchScratch;
  }

  /// agent-owns

  public int indexOfVariable(Class<? extends Agent> agentClass, String name) {
    if (agentClass == Observer.class) {
      return observerOwnsIndexOf(name);
    } else if (agentClass == Turtle.class) {
      return turtlesOwnIndexOf(name);
    } else if (agentClass == Link.class) {
      return linksOwnIndexOf(name);
    } else // patch
    {
      return patchesOwnIndexOf(name);
    }
  }

  public int indexOfVariable(Agent agent, String name) {
    if (agent instanceof Observer) {
      return observerOwnsIndexOf(name);
    } else if (agent instanceof Turtle) {
      AgentSet breed = ((Turtle) agent).getBreed();
      if (breed != _turtles) {
        int result = breedsOwnIndexOf(breed, name);
        if (result != -1) {
          return result;
        }
      }
      return turtlesOwnIndexOf(name);
    } else if (agent instanceof Link) {
      AgentSet breed = ((Link) agent).getBreed();
      if (breed != _links) {
        int result = linkBreedsOwnIndexOf(breed, name);
        if (result != -1) {
          return result;
        }
      }
      return linksOwnIndexOf(name);
    } else // patch
    {
      return patchesOwnIndexOf(name);
    }
  }

  public String turtlesOwnNameAt(int index) {
    return _program.turtlesOwn().get(index);
  }

  public int turtlesOwnIndexOf(String name) {
    return _program.turtlesOwn().indexOf(name);
  }

  public int linksOwnIndexOf(String name) {
    return _program.linksOwn().indexOf(name);
  }

  public String linksOwnNameAt(int index) {
    return _program.linksOwn().get(index);
  }

  int oldTurtlesOwnIndexOf(String name) {
    return oldTurtlesOwn.indexOf(name);
  }

  int oldLinksOwnIndexOf(String name) {
    return oldLinksOwn.indexOf(name);
  }

  public String breedsOwnNameAt(org.nlogo.api.AgentSet breed, int index) {
    List<String> breedOwns = _program.breedsOwn().get(breed.printName());
    return breedOwns.get(index - _program.turtlesOwn().size());
  }

  public int breedsOwnIndexOf(AgentSet breed, String name) {
    List<String> breedOwns = _program.breedsOwn().get(breed.printName());
    if (breedOwns == null) {
      return -1;
    }
    int result = breedOwns.indexOf(name);
    if (result == -1) {
      return -1;
    }
    return breed.type() == Turtle.class
        ? _program.turtlesOwn().size() + result
        : _program.linksOwn().size() + result;
  }

  public String linkBreedsOwnNameAt(AgentSet breed, int index) {
    List<String> breedOwns = _program.linkBreedsOwn().get(breed.printName());
    return breedOwns.get(index - _program.linksOwn().size());
  }

  public int linkBreedsOwnIndexOf(AgentSet breed, String name) {
    List<String> breedOwns = _program.linkBreedsOwn().get(breed.printName());
    if (breedOwns == null) {
      return -1;
    }
    int result = breedOwns.indexOf(name);
    if (result == -1) {
      return -1;
    }
    return _program.linksOwn().size() + result;
  }

  /**
   * used by Turtle.realloc()
   */
  int oldBreedsOwnIndexOf(AgentSet breed, String name) {
    List<String> breedOwns = oldBreedsOwn.get(breed.printName());
    if (breedOwns == null) {
      return -1;
    }
    int result = breedOwns.indexOf(name);
    if (result == -1) {
      return -1;
    }
    return oldTurtlesOwn.size() + result;
  }

  /**
   * used by Link.realloc()
   */
  int oldLinkBreedsOwnIndexOf(AgentSet breed, String name) {
    List<String> breedOwns = oldLinkBreedsOwn.get(breed.printName());
    if (breedOwns == null) {
      return -1;
    }
    int result = breedOwns.indexOf(name);
    if (result == -1) {
      return -1;
    }
    return oldLinksOwn.size() + result;
  }

  public String patchesOwnNameAt(int index) {
    return _program.patchesOwn().get(index);
  }

  public int patchesOwnIndexOf(String name) {
    return _program.patchesOwn().indexOf(name);
  }

  public String observerOwnsNameAt(int index) {
    return _program.globals().get(index);
  }

  public int observerOwnsIndexOf(String name) {
    return _program.globals().indexOf(name);
  }

  /// breeds & shapes

  public boolean isBreed(AgentSet breed) {
    return _program.breeds().containsValue(breed);
  }

  public boolean isLinkBreed(AgentSet breed) {
    return _program.linkBreeds().containsValue(breed);
  }

  public AgentSet getBreed(String breedName) {
    return (AgentSet) _program.breeds().get(breedName);
  }

  public AgentSet getLinkBreed(String breedName) {
    return (AgentSet) _program.linkBreeds().get(breedName);
  }

  public String getBreedSingular(AgentSet breed) {
    if (breed == _turtles) {
      return "TURTLE";
    }

    String breedName = breed.printName();
    for (Map.Entry<String, String> entry : _program.breedsSingular().entrySet()) {
      if (entry.getValue().equals(breedName)) {
        return entry.getKey();
      }
    }

    return "TURTLE";
  }

  public String getLinkBreedSingular(AgentSet breed) {
    if (breed == _links) {
      return "LINK";
    }

    String breedName = breed.printName();
    for (Map.Entry<String, String> entry : _program.linkBreedsSingular().entrySet()) {
      if (entry.getValue().equals(breedName)) {
        return entry.getKey();
      }
    }

    return "LINK";
  }

  // assumes caller has already checked to see if the breeds are equal
  public int compareLinkBreeds(AgentSet breed1, AgentSet breed2) {
    for (Iterator<Object> iter = _program.linkBreeds().values().iterator();
         iter.hasNext();) {
      AgentSet next = (AgentSet) iter.next();
      if (next == breed1) {
        return -1;
      } else if (next == breed2) {
        return 1;
      }
    }

    throw new IllegalStateException("neither of the breeds exist, that's bad");
  }

  public int getVariablesArraySize(Observer observer) {
    return _program.globals().size();
  }

  public int getVariablesArraySize(Patch patch) {
    return _program.patchesOwn().size();
  }

  public int getVariablesArraySize(org.nlogo.api.Turtle turtle, org.nlogo.api.AgentSet breed) {
    if (breed == _turtles) {
      return _program.turtlesOwn().size();
    } else {
      List<String> breedOwns =
          _program.breedsOwn().get(breed.printName());
      return _program.turtlesOwn().size() + breedOwns.size();
    }
  }

  public int getVariablesArraySize(org.nlogo.api.Link link, org.nlogo.api.AgentSet breed) {
    if (breed == _links) {
      return _program.linksOwn().size();
    } else {
      List<String> breedOwns =
          _program.linkBreedsOwn().get(breed.printName());
      return _program.linksOwn().size() + breedOwns.size();
    }
  }

  public int getLinkVariablesArraySize(AgentSet breed) {
    if (breed == _links) {
      return _program.linksOwn().size();
    } else {
      List<String> breedOwns =
          _program.linkBreedsOwn().get(breed.printName());
      return _program.linksOwn().size() + breedOwns.size();
    }
  }

  public String checkTurtleShapeName(String name) {
    name = name.toLowerCase();
    if (_turtleShapeList.exists(name)) {
      return name;
    } else {
      return null; // indicates failure
    }
  }

  public String checkLinkShapeName(String name) {
    name = name.toLowerCase();
    if (_linkShapeList.exists(name)) {
      return name;
    } else {
      return null; // indicates failure
    }
  }

  public Shape getLinkShape(String name) {
    return _linkShapeList.shape(name);
  }

  // use of this method by other classes is discouraged
  // since that's poor information-hiding
  public Map<String, Object> getBreeds() {
    return _program.breeds();
  }

  public boolean breedOwns(AgentSet breed, String name) {
    if (breed == _turtles) {
      return false;
    }
    List<String> breedOwns =
        _program.breedsOwn().get(breed.printName());
    return breedOwns.contains(name);
  }

  public Map<String, Object> getLinkBreeds() {
    return _program.linkBreeds();
  }

  public boolean linkBreedOwns(AgentSet breed, String name) {
    if (breed == _links) {
      return false;
    }
    List<String> breedOwns =
        _program.linkBreedsOwn().get(breed.printName());
    return breedOwns.contains(name);
  }

  public final BreedShapes linkBreedShapes = new BreedShapes("LINKS");
  public final BreedShapes turtleBreedShapes = new BreedShapes("TURTLES");

  /// program

  private Program _program = newProgram();

  public Program program() {
    return _program;
  }

  public void program(Program program) {
    if (program == null) {
      throw new IllegalArgumentException
          ("World.program cannot be set to null");
    }
    _program = program;
  }

  public Program newProgram() {
    return new Program(false);
  }

  public Program newProgram(List<String> interfaceGlobals) {
    return new Program(interfaceGlobals, false);
  }

  List<String> oldTurtlesOwn = new ArrayList<String>();
  List<String> oldPatchesOwn = new ArrayList<String>();
  List<String> oldLinksOwn = new ArrayList<String>();
  List<String> oldGlobals = new ArrayList<String>();
  Map<String, Object> oldBreeds = new LinkedHashMap<String, Object>();
  Map<String, Object> oldLinkBreeds = new LinkedHashMap<String, Object>();
  Map<String, List<String>> oldBreedsOwn = new HashMap<String, List<String>>();
  Map<String, List<String>> oldLinkBreedsOwn = new HashMap<String, List<String>>();

  public void rememberOldProgram() {
    // we could just keep the whole Program object around, but
    // that would mean all the Procedure objects couldn't be
    // GC'ed, which could possibly lead to a PermGen shortage.
    // that's just a hypothesis, about the PermGen, but in any
    // case only keeping the info we need will certainly reduce
    // overall RAM usage - ST 12/4/07
    oldTurtlesOwn = _program.turtlesOwn();
    oldPatchesOwn = _program.patchesOwn();
    oldLinksOwn = _program.linksOwn();
    oldGlobals = _program.globals();
    oldBreeds = _program.breeds();
    oldLinkBreeds = _program.linkBreeds();
    oldBreedsOwn = _program.breedsOwn();
    oldLinkBreedsOwn = _program.linkBreedsOwn();
  }

  /// display on/off

  private boolean displayOn = true;

  public boolean displayOn() {
    return displayOn;
  }

  public void displayOn(boolean displayOn) {
    this.displayOn = displayOn;
  }

  /// accessing observer variables by name;

  public Object getObserverVariableByName(String var) {
    int index = _program.globals().indexOf(var.toUpperCase());
    if (index >= 0 && index < _observer.variables.length) {
      return _observer.variables[index];
    }
    throw new IllegalArgumentException
        ("\"" + var + "\" not found");
  }

  public void setObserverVariableByName(String var, Object value)
      throws AgentException, LogoException {
    var = var.toUpperCase();
    if (_program.globals().contains(var)) {
      int index = _program.globals().indexOf(var);
      if (-1 != index && index < _observer.variables.length) {
        _observer.setObserverVariable(index, value);
        return;
      }
    }
    throw new IllegalArgumentException
        ("\"" + var + "\" not found");
  }

  private CompilerServices _compiler;

  public void compiler_$eq(CompilerServices compiler) {
    _compiler = compiler;
  }

  public CompilerServices compiler() {
    return _compiler;
  }

  public scala.collection.Iterator<Object> allStoredValues() {
    return AllStoredValues.apply(this);
  }

}
