package com.thundernotes.data.seed

/**
 * The 60 preinstalled engineering-subject cover templates (spec §6.8: "at least
 * 50 pre-downloaded cover pages for engineering subjects").
 *
 * Names mirror the [CoverSelectionPage mock](https://github.com/langergitanu/ThunderNotes-Frontend/blob/main/CoverSelectionPage/coverSelectionPage.html)
 * — Topology-Torus, Complex Plane, Venn, Matrix, Rhombus, Triangle, Circular
 * Orbits, Eigen Vectors, Waveform, Isometric Box, … — expanded to 60 across
 * Math / Physics / Electrical / CSE / Chemistry / General so the cover picker
 * shows 50+ items on first launch with no asset download.
 *
 * `colorIndex` (0–5) reuses [com.thundernotes.ui.common.FolderColors] so each
 * category gets a visually distinct cover-band color in the picker.
 *
 * Pure + unit-testable (count ≥ 50 + no duplicate display names).
 */
data class TemplateSeed(
    val displayName: String,
    val category: String,
    val colorIndex: Int,
)

object TemplatesSeed {

    val ALL: List<TemplateSeed> = listOf(
        // ── Mathematics ──────────────────────────────────────────────────────
        TemplateSeed("Topology — Torus", "Mathematics", 0),
        TemplateSeed("Complex Plane", "Mathematics", 0),
        TemplateSeed("Venn Diagram", "Mathematics", 0),
        TemplateSeed("Matrix Grid", "Mathematics", 0),
        TemplateSeed("Rhombus", "Mathematics", 0),
        TemplateSeed("Triangle Geometry", "Mathematics", 0),
        TemplateSeed("Eigen Vectors", "Mathematics", 0),
        TemplateSeed("Calculus — Integral", "Mathematics", 0),
        TemplateSeed("Vector Field", "Mathematics", 0),
        TemplateSeed("Coordinate System", "Mathematics", 0),
        TemplateSeed("Parabola", "Mathematics", 0),
        TemplateSeed("Sine Wave", "Mathematics", 0),
        TemplateSeed("Logarithm Curve", "Mathematics", 0),
        TemplateSeed("Probability Distribution", "Mathematics", 0),
        TemplateSeed("Fractal Tree", "Mathematics", 0),

        // ── Physics & Mechanics ─────────────────────────────────────────────
        TemplateSeed("Circular Orbits", "Physics", 1),
        TemplateSeed("Waveform", "Physics", 1),
        TemplateSeed("Isometric Box", "Physics", 1),
        TemplateSeed("Pendulum", "Physics", 1),
        TemplateSeed("Free-Body Diagram", "Physics", 1),
        TemplateSeed("Optics — Ray Diagram", "Physics", 1),
        TemplateSeed("Magnetic Field Lines", "Physics", 1),
        TemplateSeed("Nuclear Model", "Physics", 1),
        TemplateSeed("Quantum Wavefunction", "Physics", 1),
        TemplateSeed("Relativity Spacetime", "Physics", 1),
        TemplateSeed("Thermodynamics Cycle", "Physics", 1),
        TemplateSeed("Projectile Motion", "Physics", 1),

        // ── Electrical ──────────────────────────────────────────────────────
        TemplateSeed("Resistor Network", "Electrical", 2),
        TemplateSeed("Capacitor", "Electrical", 2),
        TemplateSeed("Inductor", "Electrical", 2),
        TemplateSeed("Transistor", "Electrical", 2),
        TemplateSeed("Diode Symbol", "Electrical", 2),
        TemplateSeed("Logic Gate", "Electrical", 2),
        TemplateSeed("Ohm's Law", "Electrical", 2),
        TemplateSeed("AC / DC Circuit", "Electrical", 2),
        TemplateSeed("Op-Amp Diagram", "Electrical", 2),
        TemplateSeed("Transformer", "Electrical", 2),
        TemplateSeed("Breadboard Layout", "Electrical", 2),
        TemplateSeed("PCB Layout", "Electrical", 2),
        TemplateSeed("Truth Table", "Electrical", 2),
        TemplateSeed("Karnaugh Map", "Electrical", 2),

        // ── Computer Science & Engineering ──────────────────────────────────
        TemplateSeed("Binary Tree", "CSE", 3),
        TemplateSeed("Flowchart", "CSE", 3),
        TemplateSeed("Stack & Queue", "CSE", 3),
        TemplateSeed("Linked List", "CSE", 3),
        TemplateSeed("Hash Table", "CSE", 3),
        TemplateSeed("Graph — BFS/DFS", "CSE", 3),
        TemplateSeed("Sorting Algorithm", "CSE", 3),
        TemplateSeed("Recursion Trace", "CSE", 3),
        TemplateSeed("Big-O Complexity", "CSE", 3),
        TemplateSeed("State Machine", "CSE", 3),
        TemplateSeed("UML Class Diagram", "CSE", 3),
        TemplateSeed("ER Database Schema", "CSE", 3),

        // ── Chemistry ───────────────────────────────────────────────────────
        TemplateSeed("Benzene Ring", "Chemistry", 4),
        TemplateSeed("Reaction Equation", "Chemistry", 4),
        TemplateSeed("Molecule Structure", "Chemistry", 4),
        TemplateSeed("Periodic Table Snippet", "Chemistry", 4),

        // ── General purpose ────────────────────────────────────────────────
        TemplateSeed("Blank — Lined", "General", 5),
        TemplateSeed("Blank — Dotted", "General", 5),
        TemplateSeed("Blank — Grid", "General", 5),
        TemplateSeed("Blank — Plain", "General", 5),
        TemplateSeed("Cornell Notes", "General", 5),
        TemplateSeed("Meeting Minutes", "General", 5),
        TemplateSeed("Lab Report", "General", 5),
    )

    /** Convenience: the count is guaranteed ≥ 50 per spec §6.8. */
    val count: Int get() = ALL.size
}
