#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Builds a detailed technical PDF explaining how blindnavandroid achieves
sub-meter walking-navigation accuracy. Reads the actual Kotlin sources so
every code excerpt is verbatim, then renders HTML -> PDF via headless Chrome.
"""
import html
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "app/src/main/java/com/google/ar/core/codelabs/hellogeospatial")

KEYWORDS = {
    "fun", "val", "var", "class", "object", "companion", "private", "public",
    "internal", "override", "return", "if", "else", "when", "for", "while",
    "in", "is", "as", "null", "true", "false", "this", "super", "import",
    "package", "data", "lateinit", "try", "catch", "throw", "break",
    "continue", "interface", "sealed", "abstract", "open", "const", "by",
    "init", "do", "enum", "typealias", "operator", "vararg", "suspend",
}


def read_lines(relpath, start=None, end=None):
    path = os.path.join(SRC, relpath)
    with open(path, "r", encoding="utf-8") as f:
        lines = f.readlines()
    if start is None:
        chunk = lines
        base = 1
    else:
        chunk = lines[start - 1:end]
        base = start
    return chunk, base


def highlight_kotlin(escaped_line):
    """Conservative highlighting on an already HTML-escaped line."""
    stripped = escaped_line.lstrip()
    if stripped.startswith("//") or stripped.startswith("/*") or stripped.startswith("*"):
        return '<span class="cm">' + escaped_line + "</span>"

    def repl(m):
        w = m.group(0)
        if w in KEYWORDS:
            return '<span class="kw">' + w + "</span>"
        return w

    # only whole words; replacements are not re-scanned by re.sub
    return re.sub(r"[A-Za-z_][A-Za-z0-9_]*", repl, escaped_line)


def code_block(relpath, start=None, end=None, caption=None):
    chunk, base = read_lines(relpath, start, end)
    out = []
    if caption:
        out.append('<div class="codecap">%s</div>' % html.escape(caption))
    out.append('<pre class="code"><table class="codetbl">')
    for i, raw in enumerate(chunk):
        ln = base + i
        text = raw.rstrip("\n")
        esc = html.escape(text)
        esc = highlight_kotlin(esc)
        if esc.strip() == "":
            esc = "&nbsp;"
        out.append(
            '<tr><td class="ln">%d</td><td class="cd">%s</td></tr>' % (ln, esc)
        )
    out.append("</table></pre>")
    return "\n".join(out)


# ---------------------------------------------------------------------------
# Document content. A section is ('prose', html) or ('code', kwargs).
# ---------------------------------------------------------------------------
S = []
def prose(h): S.append(("prose", h))
def code(relpath, start=None, end=None, caption=None):
    S.append(("code", dict(relpath=relpath, start=start, end=end, caption=caption)))


# ---- COVER -----------------------------------------------------------------
prose("""
<div class="cover">
  <div class="cover-kicker">Technical Deep Dive</div>
  <h1 class="cover-title">How blindnavandroid Achieves<br>Sub-Meter Walking Navigation</h1>
  <div class="cover-sub">A line-by-line explanation of the ARCore Geospatial (VPS) pipeline,
     heading fusion, route geometry, and spoken guidance that keep a walker on the
     sidewalk where Google Maps drifts into the street.</div>
  <div class="cover-meta">
    <div><b>Codebase</b> &nbsp; com.google.ar.core.codelabs.hellogeospatial</div>
    <div><b>Platform</b> &nbsp; Android &middot; ARCore Geospatial API &middot; Kotlin</div>
    <div><b>Audience</b> &nbsp; Engineers extending or reviewing the navigation core</div>
  </div>
</div>
""")

# ---- TOC -------------------------------------------------------------------
prose("""
<h2 class="toc-h">Contents</h2>
<ol class="toc">
  <li>Executive summary &mdash; why the dot doesn't drift</li>
  <li>GPS vs. VPS: the root cause of the accuracy gap</li>
  <li>System architecture</li>
  <li>Enabling Geospatial mode &amp; the session lifecycle</li>
  <li>The per-frame render loop (<span class="mono">onDrawFrame</span>)</li>
  <li>Heading fusion: the circular moving average</li>
  <li>Drawing the position on the 2D map</li>
  <li>The routing pipeline: destination &rarr; polyline</li>
  <li>Polyline decoding</li>
  <li>Route geometry &amp; on-route detection (the distance algorithm)</li>
  <li>Turn-by-turn guidance geometry (<span class="mono">ArrowAlignedGuide</span>)</li>
  <li>Spoken guidance (<span class="mono">SpeechGuide</span> / TTS)</li>
  <li>The OCR side-channel</li>
  <li>Accuracy budget, failure modes &amp; how to tighten it</li>
  <li>Appendix: end-to-end data-flow trace</li>
</ol>
""")

# ---- 1. EXEC SUMMARY -------------------------------------------------------
prose("""
<h2>1 &nbsp; Executive summary &mdash; why the dot doesn't drift</h2>
<p>The blue dot in Google Maps is positioned from <b>GNSS/GPS radio signals</b>
fused with Wi-Fi and cell information. In a city those radio signals bounce off
building faces ("urban-canyon multipath"), so the raw fix is routinely
<b>5&ndash;20&nbsp;m off</b>. Maps hides some of that by <i>snapping</i> the dot to
the nearest road centerline &mdash; which is precisely why it appears to jump from
the sidewalk into the middle of the street.</p>

<p>This app never asks the operating system where you are. Instead it turns on one
capability &mdash; ARCore <b>Geospatial mode</b> &mdash; and reads position from a
completely different source: <b>VPS, the Visual Positioning Service</b>. VPS looks
through the phone's camera, matches what it sees against Google's global 3D model of
the world (built from years of Street View imagery), and solves for where the camera
physically is relative to that known geometry. That is a computer-vision pose
estimate, not a radio triangulation, and in VPS-covered areas it delivers roughly
<b>sub-meter horizontal accuracy and a few degrees of heading accuracy</b>.</p>

<p>Everything else in the app &mdash; the moving map marker, the walking route, the
"turn left in 5 meters", the "you are drifting to the right" warnings &mdash; is built
on top of that one high-accuracy position/heading stream. The remainder of this
document walks every stage of that pipeline, in source order, and explains the math.</p>

<div class="callout">
<b>The one-line answer.</b> It is not a better GPS algorithm. It is <i>not GPS at all</i>:
camera-based visual localization against Google's Street-View-derived 3D map (ARCore
Geospatial / VPS), fused with the phone's inertial sensors, which is inherently
sub-meter where GPS is inherently several meters.
</div>
""")

# ---- 2. GPS vs VPS ---------------------------------------------------------
prose("""
<h2>2 &nbsp; GPS vs. VPS: the root cause of the accuracy gap</h2>

<table class="cmp">
<tr><th></th><th>Google Maps blue dot (GPS)</th><th>This app (ARCore VPS)</th></tr>
<tr><td>Primary signal</td><td>GNSS radio + Wi-Fi + cell</td><td>Camera imagery matched to a 3D world model</td></tr>
<tr><td>Typical urban error</td><td>5&ndash;20&nbsp;m horizontal</td><td>&sim;0.5&ndash;1&nbsp;m horizontal</td></tr>
<tr><td>Heading source</td><td>Magnetometer / inferred from motion</td><td>Visual pose + IMU, &sim;1&ndash;5&deg;</td></tr>
<tr><td>Main failure mode</td><td>Multipath in "urban canyons"</td><td>No VPS coverage / featureless / dark scenes</td></tr>
<tr><td>Cosmetic fix used</td><td>Snap-to-road (causes the street drift)</td><td>None needed &mdash; reports true position</td></tr>
</table>

<p>Because VPS derives position from <i>what the camera literally sees</i>, the reported
latitude/longitude stays glued to the true physical location of the phone &mdash; on the
sidewalk &mdash; and does not need road-snapping. That single difference is the entire
"how is it doing that" the user asked about. The rest is engineering to make that stream
usable for a walker (and specifically a blind walker).</p>
""")

# ---- 3. ARCHITECTURE -------------------------------------------------------
prose("""
<h2>3 &nbsp; System architecture</h2>
<p>The app is a single <span class="mono">Activity</span> wiring together a handful of
lifecycle-aware components. The diagram shows the flow of a position sample from the
camera all the way to the user's ear.</p>

<pre class="diagram">
                    Camera frames + IMU
                            |
                            v
             +------------------------------+
             |   ARCore Session (VPS on)    |   ARCoreSessionLifecycleHelper
             |   earth.cameraGeospatialPose |   configureSession()
             +------------------------------+
                            |  lat / lon / heading  (per frame, ~30 fps)
                            v
        +--------------------------------------------+
        |            HelloGeoRenderer                |   onDrawFrame()
        |  - circular moving average of heading      |
        |  - throttle to once per ~second            |
        +--------------------------------------------+
           |                  |                    |
           v                  v                    v
   +---------------+  +-----------------+  +------------------+
   |    MapView    |  | ArrowAlignedGuide|  |     useOCR()     |
   | 2D marker +   |  | route geometry: |  | ML Kit text on   |
   | camera follow |  | cross-track,    |  | live frame       |
   +---------------+  | look-ahead,     |  +------------------+
                      | turn detection  |
                      +-----------------+
                               |
                               v
                        +--------------+
                        |  SpeechGuide |  Android TextToSpeech
                        |  spoken cues |
                        +--------------+

   Routing (one-shot, on destination set):
   destination text/voice --> Geoapify geocode --> Google Directions API
     --> encoded polyline --> decodePoly() --> viewPointList (List&lt;LatLng&gt;)
</pre>

<p><b>Roles at a glance:</b></p>
<ul>
<li><span class="mono">HelloGeoActivity</span> &mdash; entry point; builds the components, enables Geospatial mode, wires speech-to-text for destination entry.</li>
<li><span class="mono">ARCoreSessionLifecycleHelper</span> &mdash; creates/resumes/pauses/closes the ARCore session safely across the Android lifecycle.</li>
<li><span class="mono">HelloGeoRenderer</span> &mdash; the heartbeat; runs every frame, reads the geospatial pose, smooths heading, drives the map and the guide.</li>
<li><span class="mono">MapView</span> &mdash; the 2D Google Map: user marker, route polyline, camera that follows heading.</li>
<li><span class="mono">ArrowAlignedGuide</span> &mdash; pure route geometry &amp; the spoken-turn state machine (no reliance on Google's turn text).</li>
<li><span class="mono">SpeechGuide</span> &mdash; a thin, thread-safe wrapper over Android TextToSpeech tuned for navigation audio.</li>
</ul>
""")

# ---- 4. ENABLE GEO + LIFECYCLE --------------------------------------------
prose("""
<h2>4 &nbsp; Enabling Geospatial mode &amp; the session lifecycle</h2>
<p>Everything hinges on one configuration flag. Without it, <span class="mono">session.earth</span>
is unavailable and there is no VPS position at all.</p>
""")
code("HelloGeoActivity.kt", 172, 180, "HelloGeoActivity.kt — the switch that turns on VPS")
prose("""
<p><span class="mono">GeospatialMode.ENABLED</span> tells ARCore to start the VPS
localization pipeline: it begins streaming visual features to Google's localization
service and fusing the returned world-anchored pose with on-device visual-inertial
odometry (SLAM). From that point on, <span class="mono">session.earth.cameraGeospatialPose</span>
yields Earth-referenced latitude, longitude, altitude, and heading.</p>

<p>This <span class="mono">configureSession</span> is registered as
<span class="mono">beforeSessionResume</span> so it runs every time the session is
about to resume &mdash; the lifecycle helper below guarantees a session exists and is
configured before <span class="mono">resume()</span>.</p>
""")
code("helpers/ARCoreSessionLifecycleHelper.kt", 65, 102,
     "ARCoreSessionLifecycleHelper.kt — safe creation & resume")
prose("""
<p>Key correctness points here:</p>
<ul>
<li><b>Permission &amp; install gating.</b> <span class="mono">tryCreateSession()</span> returns
<span class="mono">null</span> (no crash) if the CAMERA permission is missing or if Google Play
Services for AR needs installing/updating; it is retried on the next resume once the user
complies.</li>
<li><b>Configure-then-resume ordering.</b> <span class="mono">beforeSessionResume?.invoke(session)</span>
(which is <span class="mono">configureSession</span>) runs <i>before</i> <span class="mono">session.resume()</span>,
so VPS is enabled the moment the camera starts.</li>
<li><b>Deterministic teardown.</b> <span class="mono">onPause</span> pauses and
<span class="mono">onDestroy</span> closes the session, releasing the camera and native VPS
resources &mdash; important because the camera is a shared, exclusive device.</li>
</ul>
""")

# ---- 5. RENDER LOOP --------------------------------------------------------
prose("""
<h2>5 &nbsp; The per-frame render loop</h2>
<p><span class="mono">onDrawFrame</span> is the app's heartbeat &mdash; ARCore calls it
once per camera frame (~30&nbsp;fps, throttled to the camera rate because the session runs
in blocking update mode). Each call advances the AR frame, then &mdash; only when the Earth
subsystem is confidently <span class="mono">TRACKING</span> &mdash; reads a fresh geospatial
pose and fans it out.</p>
""")
code("HelloGeoRenderer.kt", 224, 280,
     "HelloGeoRenderer.kt — the geospatial branch of onDrawFrame")
prose("""
<p>Walking through it:</p>
<ul>
<li><b>Gate on tracking (line 225).</b> Nothing downstream runs unless
<span class="mono">earth.trackingState == TRACKING</span>. This is what prevents the app from
ever acting on a low-confidence or not-yet-localized pose &mdash; the equivalent of Maps
showing a "searching…" grey dot, but enforced in code.</li>
<li><b>Read the pose (line 226).</b> <span class="mono">cameraGeospatialPose</span> is the VPS
output: <span class="mono">.latitude</span>, <span class="mono">.longitude</span>,
<span class="mono">.heading</span>, <span class="mono">.altitude</span>.</li>
<li><b>Smooth the heading (lines 228&ndash;242).</b> Raw per-frame heading is jittery, so it is
run through a circular moving average &mdash; detailed in the next section.</li>
<li><b>Push to the map (line 244).</b> <span class="mono">updateMapPosition</span> moves the 2D
marker and, when navigating, rotates the map to the walker's facing.</li>
<li><b>Throttle the guidance (line 255).</b> <span class="mono">curFrame++ == oneSecond</span>
(<span class="mono">oneSecond = 29</span>) fires the navigation update roughly once per second
rather than every frame &mdash; guidance geometry and speech don't need 30&nbsp;Hz, and this
keeps the spoken cues calm.</li>
<li><b>Arrival &amp; off-route UX (lines 266&ndash;276).</b> On the UI thread it updates the
status text, declares arrival under 1.5&nbsp;m, and surfaces a "Veering off" snackbar when the
guide reports the walker has left the route corridor.</li>
</ul>
<div class="callout small">
<b>Why gate everything on <span class="mono">TRACKING</span>?</b> A VPS pose that hasn't
converged can be tens of meters wrong for a moment. By refusing to move the marker, speak, or
evaluate the route until the state is <span class="mono">TRACKING</span>, the app trades a short
startup delay for never emitting a confidently-wrong instruction to a blind user.
</div>
""")

# ---- 6. HEADING FUSION -----------------------------------------------------
prose("""
<h2>6 &nbsp; Heading fusion: the circular moving average</h2>
<p>Heading is an <b>angle</b>, so you cannot average it arithmetically: the mean of
359&deg; and 1&deg; is 180&deg; (pointing backwards) when the true answer is 0&deg;. The
renderer solves this correctly by averaging the <i>unit vectors</i> of the recent headings
and taking the resulting vector's angle &mdash; the standard "mean of circular quantities".</p>
""")
code("HelloGeoRenderer.kt", 228, 249,
     "HelloGeoRenderer.kt — windowed circular mean of heading")
prose("""
<p>Mechanics:</p>
<ul>
<li><b>Sliding window.</b> <span class="mono">headingValues</span> is an
<span class="mono">ArrayDeque</span> holding up to <span class="mono">oneSecond&nbsp;=&nbsp;29</span>
recent samples (~1&nbsp;s at 30&nbsp;fps). Older samples are dropped from the front.</li>
<li><b>Vector sum.</b> Each heading &theta; contributes
(cos&nbsp;&theta;,&nbsp;sin&nbsp;&theta;); summing these and taking
<span class="mono">atan2(&Sigma;sin, &Sigma;cos)</span> gives the average direction with no
wraparound error.</li>
<li><b>Normalize to &plusmn;180&deg;.</b> The result is folded into the range the map and guide
expect (<span class="mono">if (avgHeading &gt; 180) avgHeading -= 360</span>).</li>
</ul>
<p>The net effect: a stable, low-lag facing direction. This matters more here than in a
typical map app, because the <b>heading</b> &mdash; not just position &mdash; drives the
spoken "turn left / turn right" instructions. A jittery heading would make the guide
stutter contradictory turn cues; the smoothing is what makes the voice guidance trustworthy.</p>
<div class="callout small">
<b>Note.</b> There is a deliberate shadowing here: the running scalar
<span class="mono">avgHeading</span> field is maintained but then a local
<span class="mono">avgHeading</span> (the circular mean) is what's actually used and passed
downstream. The circular-mean local is the correct value; the scalar accumulator is vestigial.
</div>
""")

# ---- 7. MAP DISPLAY --------------------------------------------------------
prose("""
<h2>7 &nbsp; Drawing the position on the 2D map</h2>
<p><span class="mono">updateMapPosition</span> receives the smoothed pose and reconciles it
with the Google Map. It is careful not to fight the user's own map gestures.</p>
""")
code("helpers/MapView.kt", 141, 183, "MapView.kt — marker + heading-locked camera follow")
prose("""
<ul>
<li><b>Don't fight the user (line 145).</b> If the map camera is mid-move
(<span class="mono">!cameraIdle</span>), the update returns early so a programmatic camera move
doesn't yank the view while the user is panning.</li>
<li><b>Marker is the true VPS position (lines 148&ndash;150).</b> The green navigation marker is
placed at the VPS lat/lng and <i>rotated</i> to the smoothed heading &mdash; so the arrow points
where the walker actually faces.</li>
<li><b>First-fix framing (lines 152&ndash;166).</b> On the first sample it zooms in tight
(<span class="mono">zoom(21f)</span> &mdash; building-level) and centers; afterward it preserves
the user's chosen zoom.</li>
<li><b>Heading-up navigation (lines 167&ndash;178).</b> Once a destination is set
(<span class="mono">isClick</span>), the camera <span class="mono">bearing</span> is locked to the
walker's heading, giving an egocentric "the world rotates around me" view and enabling the compass.
The heading is wrapped to [0,360) for the Maps API.</li>
</ul>
""")

# ---- 8. ROUTING PIPELINE ---------------------------------------------------
prose("""
<h2>8 &nbsp; The routing pipeline: destination &rarr; polyline</h2>
<p>Routing is a one-shot operation triggered when the user sets a destination &mdash; either
by tapping the map, or by typing/speaking a place name. The accuracy story is entirely on the
<i>positioning</i> side; the route itself is a standard Google Directions walking route. Here is
the tap path.</p>
""")
code("HelloGeoRenderer.kt", 411, 469, "HelloGeoRenderer.kt — onMapClick: build the route & anchor")
prose("""
<p>Step by step:</p>
<ul>
<li><b>Origin = VPS position (lines 425&ndash;427).</b> The route's origin is the current
<span class="mono">cameraGeospatialPose</span>, i.e. the sub-meter VPS fix &mdash; not a GPS fix.
A precise origin is what lets the corridor math later be meaningful at a 3&ndash;5&nbsp;m scale.</li>
<li><b>Ask Google Directions (lines 429&ndash;430).</b> <span class="mono">getDirectionsUrl</span>
builds a walking-mode request; <span class="mono">fetchJson</span> executes it off the main thread.</li>
<li><b>Decode &amp; store the path (lines 435&ndash;441).</b> Each step's encoded
<span class="mono">polyline</span> is expanded to <span class="mono">LatLng</span> points and appended
to <span class="mono">viewPointList</span>, then drawn as a red polyline.</li>
<li><b>Hand the geometry to the guide (lines 444&ndash;451).</b> Once there are &ge;2 points,
<span class="mono">navGuide.onRouteReady</span> primes the turn-by-turn state machine with the path
and the current pose.</li>
<li><b>Drop a world anchor (lines 455&ndash;464).</b> An ARCore <span class="mono">Earth</span> anchor
is created at the destination lat/lng so the 3D marker is pinned to real-world geometry, tracked as
ARCore refines its estimate.</li>
</ul>
<p>The request URL is deliberately simple &mdash; walking mode, JSON output:</p>
""")
code("HelloGeoRenderer.kt", 535, 548, "HelloGeoRenderer.kt — Directions request builder")
prose("""
<p>The typed/spoken path (in <span class="mono">HelloGeoView</span>) adds a geocoding hop: it
reverse-geocodes the current VPS position to a postcode (Geoapify), then forward-geocodes the
spoken place name constrained to a 5&nbsp;km circle around the user, and finally calls the same
<span class="mono">onMapClick</span> with the resolved coordinate.</p>
""")
code("helpers/HelloGeoView.kt", 79, 111, "HelloGeoView.kt — destination submit (voice/text)")

# ---- 9. POLYLINE DECODE ----------------------------------------------------
prose("""
<h2>9 &nbsp; Polyline decoding</h2>
<p>Google returns each route step's geometry as an <b>encoded polyline</b> &mdash; a compact
ASCII string. <span class="mono">decodePoly</span> is the standard Google algorithm: it reads
variable-length, zig-zag-encoded, 5-decimal-place deltas and reconstructs the
<span class="mono">LatLng</span> vertices.</p>
""")
code("HelloGeoRenderer.kt", 502, 533, "HelloGeoRenderer.kt — encoded-polyline decoder")
prose("""
<p>For each coordinate it accumulates 5-bit groups until the continuation bit clears, undoes the
zig-zag (<span class="mono">(result shr 1).inv()</span> for negatives), scales by 1e-5, and adds the
delta to the running lat/lng. These vertices become the corridor centerline the guidance geometry
measures against.</p>
""")

# ---- 10. DISTANCE ALGORITHM ------------------------------------------------
prose("""
<h2>10 &nbsp; Route geometry &amp; on-route detection (the distance algorithm)</h2>
<p>This is the heart of "am I still on the sidewalk path?" Given the precise VPS position and the
decoded route, the app computes the walker's <b>perpendicular (cross-track) distance</b> to the
nearest route segment and compares it against a tolerance. Because the position is sub-meter, the
tolerance can be a tight few meters &mdash; impossible with GPS noise.</p>
""")
code("HelloGeoRenderer.kt", 363, 389, "HelloGeoRenderer.kt — isInRoute: nearest-segment corridor test")
prose("""
<p>For every consecutive pair of route points it forms a triangle between the segment endpoints and
the walker, and uses the triangle's geometry to get the perpendicular distance:</p>
""")
code("HelloGeoRenderer.kt", 346, 360, "HelloGeoRenderer.kt — distanceFromPointToLine (Heron's formula)")
prose("""
<p>The math, decoded:</p>
<ul>
<li><b>Near-vertex short-circuit (line 348).</b> If the walker is within
<span class="mono">FIX_DISTANCE&times;1.2</span> of <i>both</i> endpoints, just return the
start-to-current distance &mdash; near a waypoint the perpendicular is meaningless.</li>
<li><b>Projection falls outside the segment (line 352).</b> If
<span class="mono">startToCur + endToCur &le; startToEnd</span> (degenerate/collinear-beyond case) it
returns 0.</li>
<li><b>Heron's formula (lines 355&ndash;358).</b> Otherwise it computes the triangle area from the
three side lengths via the semi-perimeter <span class="mono">p</span>, then
<span class="mono">perpendicular = 2&middot;area / base</span>, where the base is the route segment
<span class="mono">startToEnd</span>. That perpendicular <i>is</i> the cross-track distance.</li>
</ul>
<p>The <span class="mono">isInRoute</span> wrapper additionally guards with the two base angles
(<span class="mono">angleA</span>, <span class="mono">angleB</span>): only when both are acute is the
foot of the perpendicular actually on the segment, so the perpendicular distance is valid. It keeps
the minimum across all segments and declares "on route" when that minimum is within
<span class="mono">FIX_DISTANCE (3&nbsp;m)</span>. The great-circle distance between any two coordinates
uses the spherical law of cosines:</p>
""")
code("HelloGeoRenderer.kt", 569, 581, "HelloGeoRenderer.kt — great-circle distance (meters)")
prose("""
<p>(<span class="mono">6367</span> is Earth's radius in km; the result is scaled to meters.) The same
formula appears, hardened with a <span class="mono">coerceIn(-1.0, 1.0)</span> domain clamp, inside
<span class="mono">ArrowAlignedGuide</span> &mdash; the clamp prevents <span class="mono">acos</span> from
returning <span class="mono">NaN</span> on tiny floating-point overshoots for near-zero distances.</p>
""")

# ---- 11. GUIDANCE GEOMETRY -------------------------------------------------
prose("""
<h2>11 &nbsp; Turn-by-turn guidance geometry</h2>
<p><span class="mono">ArrowAlignedGuide</span> is a self-contained module that converts
(position, heading, path) into human instructions. Notably it <b>ignores Google's turn text</b>
and derives turns purely from geometry &mdash; so guidance stays correct even when the walker is
between the coarse waypoints Google provides. Its tuning constants define the whole behavior:</p>
""")
code("helpers/ArrowAlignedGuide.kt", 17, 30, "ArrowAlignedGuide.kt — corridor & turn tuning")
prose("""
<p>When a route is set, it snaps to the nearest segment and speaks an initial summary:</p>
""")
code("helpers/ArrowAlignedGuide.kt", 61, 77, "ArrowAlignedGuide.kt — onRouteReady")
prose("""
<p>Then, once per second, <span class="mono">update</span> recomputes the full situational picture:</p>
""")
code("helpers/ArrowAlignedGuide.kt", 79, 130, "ArrowAlignedGuide.kt — per-tick update")
prose("""
<p>The important geometric primitives it composes:</p>
<ul>
<li><b>Absolute bearing (lines 272&ndash;280).</b> The compass bearing from the walker to a target
point &mdash; standard <span class="mono">atan2</span> forward-azimuth formula, normalized to [0,360).</li>
<li><b>Relative bearing (lines 268&ndash;270).</b> Target bearing minus current heading, normalized to
[-180,180]: positive means "the path is to your right", negative "to your left". This single signed
number becomes "turn left/right".</li>
<li><b>Look-ahead point (lines 198&ndash;221).</b> Rather than aim at the next raw vertex, it walks
<span class="mono">lookAheadMeters (12&nbsp;m)</span> forward along the polyline from the walker's
projected position and aims there &mdash; giving smooth "continue straight / gentle right" cues instead
of snapping at each vertex.</li>
<li><b>Cross-track &amp; side (lines 323&ndash;361).</b> The same Heron's-formula perpendicular as above,
plus a 2D cross-product sign test (<span class="mono">isLeftOfSegment</span>) to say whether the drift is
to the left or right &mdash; which is what powers "you are drifting to the right of the path."</li>
</ul>
""")
code("helpers/ArrowAlignedGuide.kt", 268, 291, "ArrowAlignedGuide.kt — bearing primitives")
prose("""
<p>Finally, the countdown state machine turns geometry into speech without nagging:</p>
""")
code("helpers/ArrowAlignedGuide.kt", 150, 178, "ArrowAlignedGuide.kt — announceTurnThresholds")
prose("""
<ul>
<li><b>Only for real turns (line 159).</b> Countdowns fire only when the upcoming path differs from the
current facing by more than <span class="mono">TURN_HINT_DEG (35&deg;)</span>.</li>
<li><b>Debounced thresholds (lines 163&ndash;171).</b> It announces "turn in 15/10/5 meters" at most once
each, and marks higher thresholds consumed if the walker enters mid-band &mdash; so it never repeats or
counts up.</li>
<li><b>Final commit (lines 173&ndash;177).</b> Within <span class="mono">TURN_NOW_M (2.5&nbsp;m)</span> it
flushes a "turn left/right now."</li>
<li><b>Deviation &amp; periodic cues</b> (in <span class="mono">update</span>) are separately rate-limited
(<span class="mono">deviationIntervalMs = 5&nbsp;s</span>, <span class="mono">periodicIntervalMs = 10&nbsp;s</span>)
and only trigger deviation warnings after a 2-tick off-route streak to avoid false alarms from a single
noisy sample.</li>
</ul>
""")

# ---- 12. SPEECH ------------------------------------------------------------
prose("""
<h2>12 &nbsp; Spoken guidance (SpeechGuide / TTS)</h2>
<p>For a blind-navigation app the audio channel is the product. <span class="mono">SpeechGuide</span>
is a small, thread-safe wrapper over Android <span class="mono">TextToSpeech</span> tuned for the job.</p>
""")
code("helpers/SpeechGuide.kt", 14, 77, "SpeechGuide.kt — navigation-tuned TTS")
prose("""
<ul>
<li><b>Navigation audio routing (lines 34&ndash;39).</b> It sets
<span class="mono">USAGE_ASSISTANCE_NAVIGATION_GUIDANCE</span> so the OS ducks other audio (music,
podcasts) for cues instead of talking over them &mdash; and routes through the navigation audio focus.</li>
<li><b>Slightly slowed rate (line 33).</b> <span class="mono">0.92&times;</span> speech rate for clarity
while walking.</li>
<li><b>Flush vs. queue (lines 59&ndash;64).</b> Urgent cues ("turn now", "drifting") pass
<span class="mono">flush = true</span> &rarr; <span class="mono">QUEUE_FLUSH</span>, interrupting stale
speech; routine cues append with <span class="mono">QUEUE_ADD</span>.</li>
<li><b>Readiness &amp; speaking flags (@Volatile).</b> Guards prevent speaking before TTS init completes
and track whether audio is currently playing, across threads.</li>
</ul>
""")

# ---- 13. OCR ---------------------------------------------------------------
prose("""
<h2>13 &nbsp; The OCR side-channel</h2>
<p>Independent of navigation, an optional OCR mode runs Google ML Kit text recognition on the live
camera frame &mdash; e.g. to confirm a door number or sign matches the destination. It is a toggle so it
never competes with the navigation compute path unless the user asks for it.</p>
""")
code("HelloGeoRenderer.kt", 312, 343, "HelloGeoRenderer.kt — useOCR: ML Kit on the AR frame")
prose("""
<p>It acquires the current camera image, runs the on-device Latin text recognizer, lowercases the
result, and checks whether any word of the target string appears &mdash; announcing "Find!!" on a match.
Exceptions for frames that aren't ready yet (<span class="mono">NotYetAvailableException</span>) or
resource pressure are swallowed so OCR never destabilizes the render loop.</p>
""")

# ---- 14. ACCURACY BUDGET ---------------------------------------------------
prose("""
<h2>14 &nbsp; Accuracy budget, failure modes &amp; how to tighten it</h2>
<p><b>Why the end-to-end result is sub-meter:</b></p>
<ul>
<li>VPS gives a &sim;0.5&ndash;1&nbsp;m position and few-degree heading in covered areas.</li>
<li>The <span class="mono">TRACKING</span> gate discards low-confidence poses entirely.</li>
<li>Circular-mean heading smoothing removes per-frame jitter without adding meaningful lag.</li>
<li>A precise VPS origin makes the 3&nbsp;m corridor test and 12&nbsp;m look-ahead physically meaningful.</li>
</ul>
<p><b>Known limitations / failure modes:</b></p>
<ul>
<li><b>Coverage.</b> VPS needs Street-View-mapped surroundings and a camera pointed at the world.
Indoors, tunnels, dark or featureless scenes degrade or lose the fix.</li>
<li><b>Battery/compute.</b> Continuous camera + CV + TTS is heavier than a passive GPS read.</li>
<li><b>Accuracy is not consulted.</b> The code reads <span class="mono">cameraGeospatialPose</span> but never
checks <span class="mono">horizontalAccuracy</span> / <span class="mono">headingAccuracy</span>. It trusts any
<span class="mono">TRACKING</span> pose equally.</li>
</ul>
<div class="callout">
<b>Concrete hardening opportunities.</b>
<ol>
<li><b>Reject low-confidence fixes.</b> Skip the map/guide update (or soften cues) when
<span class="mono">cameraGeospatialPose.horizontalAccuracy</span> exceeds, say, 2&nbsp;m, instead of trusting
every <span class="mono">TRACKING</span> sample.</li>
<li><b>Remove the vestigial scalar <span class="mono">avgHeading</span> accumulator</b> in the renderer to
avoid confusion; the circular-mean local is the value actually used.</li>
<li><b>Move the hard-coded API keys</b> (Directions, Geoapify) out of source into a secrets/config file &mdash;
they are currently checked into the repository.</li>
<li><b>Consider gating on <span class="mono">headingAccuracy</span></b> before speaking turn cues, since heading
drives the left/right decision.</li>
</ol>
</div>
""")

# ---- 15. APPENDIX ----------------------------------------------------------
prose("""
<h2>15 &nbsp; Appendix: end-to-end data-flow trace</h2>
<p>One position sample, from photons to speech:</p>
<pre class="diagram">
1.  Camera frame + IMU delivered to ARCore session (VPS enabled).
2.  ARCore localizes visually against Google's 3D world model
       -> earth.cameraGeospatialPose { lat, lon, heading, accuracy }.
3.  onDrawFrame gate: proceed only if earth.trackingState == TRACKING.
4.  Heading pushed into 29-sample deque; circular mean computed
       avgHeading = atan2(Sigma sin theta, Sigma cos theta), folded to +/-180.
5.  MapView.updateMapPosition: green marker to (lat,lon), rotated to
       avgHeading; camera bearing locked to heading when navigating.
6.  Once per ~second (curFrame == 29) and if a route is set:
       ArrowAlignedGuide.update(user=(lat,lon), heading=avgHeading, path):
         a. advanceSegment  -> current route segment index
         b. lookAheadPoint  -> aim 12 m ahead along the polyline
         c. relativeBearing -> signed angle: +right / -left
         d. crossTrack (Heron) + isLeftOfSegment -> on-route? which side?
         e. announceTurnThresholds -> "turn left in 5 meters" (debounced)
7.  SpeechGuide.speak(...) -> Android TTS, nav-guidance audio usage,
       flush for urgent cues, queue for routine cues.
8.  UI thread: status text updated; "Arrived" under 1.5 m; "Veering off"
       snackbar when off-route.
</pre>
<p class="fin">The accuracy the user noticed comes entirely from stage&nbsp;2 &mdash; visual
localization replacing GPS. Stages&nbsp;3&ndash;8 are the engineering that turns that precise,
stable pose into trustworthy spoken walking guidance.</p>
""")


# ---------------------------------------------------------------------------
# Assemble HTML
# ---------------------------------------------------------------------------
CSS = """
@page { size: A4; margin: 18mm 16mm 20mm 16mm; }
* { box-sizing: border-box; }
body { font-family: -apple-system, "Helvetica Neue", Arial, sans-serif;
       color: #1a1f26; font-size: 10.5pt; line-height: 1.5; margin: 0; }
h1,h2,h3 { color: #0b2a4a; line-height: 1.2; }
h2 { font-size: 15pt; margin: 26px 0 8px; padding-top: 6px;
     border-top: 2px solid #0b6efd; page-break-after: avoid; }
h3 { font-size: 12pt; margin: 16px 0 6px; }
p { margin: 7px 0; }
ul,ol { margin: 6px 0 10px; padding-left: 20px; }
li { margin: 3px 0; }
b { color: #0b2a4a; }
.mono, .mono * { font-family: "SF Mono", "Menlo", Consolas, monospace; font-size: 9.2pt;
        background: #eef2f7; padding: 0 3px; border-radius: 3px; color: #0a3d62; }

/* cover */
.cover { height: 245mm; display: flex; flex-direction: column; justify-content: center;
         page-break-after: always; }
.cover-kicker { color: #0b6efd; font-weight: 700; letter-spacing: 3px;
                text-transform: uppercase; font-size: 11pt; }
.cover-title { font-size: 30pt; margin: 14px 0 18px; color: #0b2a4a; }
.cover-sub { font-size: 12.5pt; color: #3a4652; max-width: 150mm; line-height: 1.55; }
.cover-meta { margin-top: 40px; font-size: 10.5pt; color: #3a4652; }
.cover-meta div { margin: 5px 0; }

/* toc */
.toc-h { border: none; }
ol.toc { font-size: 11pt; line-height: 1.9; }
ol.toc li { margin: 0; }

/* callouts */
.callout { background: #eef5ff; border-left: 4px solid #0b6efd; padding: 10px 14px;
           margin: 14px 0; border-radius: 0 6px 6px 0; page-break-inside: avoid; }
.callout.small { font-size: 9.8pt; background: #f6f8fa; border-left-color: #7a8899; }
.callout ol, .callout ul { margin: 6px 0; }

/* comparison table */
table.cmp, table.cmp th, table.cmp td { border: 1px solid #cdd6e0; border-collapse: collapse; }
table.cmp { width: 100%; margin: 12px 0; font-size: 9.8pt; }
table.cmp th { background: #0b2a4a; color: #fff; text-align: left; padding: 7px 9px; }
table.cmp td { padding: 6px 9px; vertical-align: top; }
table.cmp tr:nth-child(even) td { background: #f3f6fa; }
table.cmp td:first-child { font-weight: 700; color: #0b2a4a; width: 22%; }

/* code */
.codecap { font-family: "SF Mono","Menlo",monospace; font-size: 8.4pt; color: #5a6b7b;
           background: #e7edf3; padding: 4px 10px; border-radius: 6px 6px 0 0;
           border: 1px solid #d0dae4; border-bottom: none; margin-top: 14px; }
pre.code { margin: 0 0 12px; background: #0f1b2d; border-radius: 0 0 6px 6px;
           padding: 8px 4px; overflow: hidden; page-break-inside: avoid; }
.codetbl { border-collapse: collapse; width: 100%; }
.codetbl td { font-family: "SF Mono","Menlo",Consolas,monospace; font-size: 8.1pt;
              line-height: 1.45; vertical-align: top; padding: 0; }
.codetbl td.ln { color: #47617e; text-align: right; padding: 0 10px 0 6px;
                 user-select: none; width: 30px; white-space: nowrap; }
.codetbl td.cd { color: #dfe8f2; white-space: pre-wrap; word-break: break-word;
                 padding-right: 8px; }
.cd .kw { color: #6fb2ff; }
.cd .cm { color: #7f9a72; font-style: italic; }

/* diagram */
pre.diagram { background: #f6f8fa; border: 1px solid #d0dae4; border-radius: 6px;
              padding: 12px 14px; font-family: "SF Mono","Menlo",monospace;
              font-size: 8.1pt; line-height: 1.35; color: #23303d;
              white-space: pre; overflow: hidden; page-break-inside: avoid; }
.fin { font-style: italic; color: #0b2a4a; border-top: 1px solid #cdd6e0;
       padding-top: 10px; margin-top: 16px; }
"""

parts = ['<!DOCTYPE html><html><head><meta charset="utf-8"><style>%s</style></head><body>' % CSS]
for kind, payload in S:
    if kind == "prose":
        parts.append(payload)
    else:
        parts.append(code_block(**payload))
parts.append("</body></html>")

out_html = os.path.join(ROOT, "docs", "how_it_works.html")
os.makedirs(os.path.dirname(out_html), exist_ok=True)
with open(out_html, "w", encoding="utf-8") as f:
    f.write("\n".join(parts))
print("wrote", out_html)

# render to PDF via headless Chrome
out_pdf = os.path.join(ROOT, "docs", "HowItWorks_SubMeterNavigation.pdf")
chrome = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
cmd = [
    chrome, "--headless=new", "--disable-gpu", "--no-sandbox",
    "--no-pdf-header-footer",
    "--print-to-pdf=%s" % out_pdf,
    "--virtual-time-budget=8000",
    "file://%s" % out_html,
]
print("running chrome...")
r = subprocess.run(cmd, capture_output=True, text=True)
sys.stderr.write(r.stderr[-1500:] if r.stderr else "")
print("\nPDF:", out_pdf, "exists:", os.path.exists(out_pdf))
