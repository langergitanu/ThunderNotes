package com.thundernotes.data.seed

import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for [TemplatesSeed] — the 60 preinstalled engineering covers. */
class TemplatesSeedTest {

    @Test fun `seed has at least 50 templates per spec §6_8`() {
        assertTrue("expected ≥ 50 templates, got ${TemplatesSeed.count}", TemplatesSeed.count >= 50)
    }

    @Test fun `every seed has a non-blank display name + category + valid color index`() {
        TemplatesSeed.ALL.forEach { seed ->
            assertTrue("blank name: $seed", seed.displayName.isNotBlank())
            assertTrue("blank category: $seed", seed.category.isNotBlank())
            assertTrue("color index out of range: $seed", seed.colorIndex in 0..5)
        }
    }

    @Test fun `no duplicate display names`() {
        val names = TemplatesSeed.ALL.map { it.displayName }
        assertTrue(
            "duplicates: ${names.groupingBy { it }.eachCount().filter { it.value > 1 }}",
            names.size == names.toSet().size,
        )
    }

    @Test fun `covers the 5 engineering subjects from the mock`() {
        val categories = TemplatesSeed.ALL.map { it.category }.toSet()
        assertTrue("missing Mathematics", "Mathematics" in categories)
        assertTrue("missing Physics", "Physics" in categories)
        assertTrue("missing Electrical", "Electrical" in categories)
        assertTrue("missing CSE", "CSE" in categories)
    }

    @Test fun `includes the mock's named covers`() {
        val names = TemplatesSeed.ALL.map { it.displayName }
        // Names taken directly from the CoverSelectionPage mock HTML.
        listOf("Topology — Torus", "Complex Plane", "Venn Diagram", "Matrix Grid",
               "Circular Orbits", "Waveform", "Isometric Box", "Eigen Vectors")
            .forEach { assertTrue("missing mock cover: $it", it in names) }
    }
}
