package dev.pinkcollab.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DirectoryPathTest {
    @Test fun unix_crumbs_stop_at_the_longest_workspace_root() {
        val crumbs = directoryCrumbs(
            "/Users/mac/code/PinkCollab/android",
            listOf("/Users/mac/code", "/Users/mac/code/PinkCollab"),
            windows = false,
        )

        assertEquals(
            listOf(
                DirectoryCrumb("PinkCollab", "/Users/mac/code/PinkCollab", current = false),
                DirectoryCrumb("android", "/Users/mac/code/PinkCollab/android", current = true),
            ),
            crumbs,
        )
    }

    @Test fun root_itself_is_a_single_current_crumb() {
        assertEquals(
            listOf(DirectoryCrumb("code", "/Users/mac/code", current = true)),
            directoryCrumbs("/Users/mac/code/", listOf("/Users/mac/code"), windows = false),
        )
    }

    @Test fun prefix_lookalike_is_not_inside_the_workspace() {
        assertEquals(
            listOf(DirectoryCrumb("code2", "/Users/mac/code2", current = true)),
            directoryCrumbs("/Users/mac/code2", listOf("/Users/mac/code"), windows = false),
        )
        assertNull(browserParentTarget("/Users/mac/code2", false, null, listOf("/Users/mac/code"), false))
    }

    @Test fun windows_crumbs_keep_the_path_separator_and_ignore_case() {
        val crumbs = directoryCrumbs(
            "F:\\Code\\PinkCollab\\android",
            listOf("f:\\code"),
            windows = true,
        )

        assertEquals(
            listOf(
                DirectoryCrumb("code", "f:\\code", current = false),
                DirectoryCrumb("PinkCollab", "f:\\code\\PinkCollab", current = false),
                DirectoryCrumb("android", "f:\\code\\PinkCollab\\android", current = true),
            ),
            crumbs,
        )
    }

    @Test fun forward_slash_windows_paths_stay_forward_slash() {
        assertEquals(
            listOf(
                DirectoryCrumb("code", "F:/code", current = false),
                DirectoryCrumb("PinkCollab", "F:/code/PinkCollab", current = true),
            ),
            directoryCrumbs("F:/code/PinkCollab", listOf("F:/code"), windows = true),
        )
    }

    @Test fun linux_matching_stays_case_sensitive() {
        assertEquals(
            listOf(DirectoryCrumb("app", "/work/app", current = true)),
            directoryCrumbs("/work/app", listOf("/Work"), windows = false),
        )
    }

    @Test fun unc_parent_stays_inside_the_share() {
        assertEquals(
            "\\\\server\\share\\proj",
            browserParentTarget(
                "\\\\server\\share\\proj\\src",
                listingReady = false,
                listingParent = null,
                roots = listOf("\\\\server\\share\\proj"),
                windows = true,
            ),
        )
    }

    @Test fun loaded_listing_parent_wins_and_a_null_parent_leaves_the_browser() {
        assertEquals(
            "/work",
            browserParentTarget("/work/app", true, "/work", listOf("/elsewhere"), false),
        )
        assertNull(browserParentTarget("/work", true, null, listOf("/work"), false))
        assertNull(browserParentTarget("F:/code", true, "F:\\code", emptyList(), true))
    }

    @Test fun synthesized_parent_uses_the_original_root_and_does_not_climb_above_it() {
        assertEquals("/work/", browserParentTarget("/work/app/", false, null, listOf("/work/"), false))
        assertEquals("/work/app", browserParentTarget("/work/app/src", false, null, listOf("/work"), false))
        assertNull(browserParentTarget("/work", false, null, listOf("/work"), false))
        assertNull(browserParentTarget("/work/app", false, null, emptyList(), false))
    }

    @Test fun leaf_keeps_drive_and_unix_roots_readable() {
        assertEquals("PinkCollab", directoryLeaf("F:/code/PinkCollab"))
        assertEquals("C:", directoryLeaf("C:\\"))
        assertEquals("/", directoryLeaf("/"))
        assertEquals("share", directoryLeaf("\\\\server\\share"))
        assertEquals("Mac Studio", browserTitle(" Mac Studio "))
        assertEquals("Browse workspace", browserTitle("   "))
    }

    @Test fun parent_crumb_uses_the_listing_parent_not_a_reconstructed_path() {
        val crumbs = directoryCrumbs("F:\\Code\\PinkCollab", listOf("f:\\code"), windows = true)
        val parent = browserParentTarget("F:\\Code\\PinkCollab", true, "F:\\Code", listOf("f:\\code"), true)
        assertEquals("F:\\Code", crumbTarget(crumbs, 0, parent, emptyList(), true))
        assertNull(crumbTarget(crumbs, crumbs.lastIndex, parent, listOf("F:\\Code"), true))
    }

    @Test fun deeper_crumb_opens_only_a_known_server_path() {
        val crumbs = directoryCrumbs("/work/app/src", listOf("/work"), windows = false)
        assertNull(crumbTarget(crumbs, 0, "/work/app", emptyList(), false))
        assertEquals("/work", crumbTarget(crumbs, 0, "/work/app", listOf("/work"), false))
        assertEquals("/work/app", crumbTarget(crumbs, 1, "/work/app", emptyList(), false))
    }

    @Test fun allowlist_boundary_disables_ancestor_navigation() {
        val crumbs = directoryCrumbs("/work/app/src", listOf("/work"), windows = false)
        assertNull(crumbTarget(crumbs, 0, null, listOf("/work"), false))
        assertNull(crumbTarget(crumbs, 1, null, listOf("/work"), false))
    }

    @Test fun noted_server_path_replaces_an_equivalent_spelling() {
        assertEquals(listOf("F:\\Code"), noteServerPath(listOf("f:\\code"), "F:\\Code", windows = true))
        assertEquals(listOf("/work", "/work/app"), noteServerPath(listOf("/work"), "/work/app", windows = false))
        assertEquals(listOf("/work"), noteServerPath(listOf("/work"), "  ", windows = false))
    }
}
