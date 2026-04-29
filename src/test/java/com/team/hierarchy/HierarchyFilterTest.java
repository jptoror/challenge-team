package com.team.hierarchy;

import org.junit.jupiter.api.Test;

import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class HierarchyFilterTest {

    @Test
    void exampleFromSpec() {
        com.team.hierarchy.Hierarchy input = h(
            new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11},
            new int[]{0, 1, 2, 3, 1, 0, 1, 0, 1,  1,  2}
        );
        Hierarchy expected = h(
            new int[]{1, 2, 5, 8, 10, 11},
            new int[]{0, 1, 1, 0,  1,  2}
        );
        assertSameShape(expected, HierarchyFilter.filter(input, id -> id % 3 != 0));
    }

    @Test
    void emptyHierarchy() {
        Hierarchy empty = h(new int[0], new int[0]);
        Hierarchy result = HierarchyFilter.filter(empty, id -> true);
        assertEquals(0, result.size());
    }

    @Test
    void predicateAcceptsAll_returnsEquivalentHierarchy() {
        Hierarchy input = h(
            new int[]{10, 20, 30, 40},
            new int[]{ 0,  1,  1,  0}
        );
        assertSameShape(input, HierarchyFilter.filter(input, id -> true));
    }

    @Test
    void predicateRejectsAll_returnsEmpty() {
        Hierarchy input = h(
            new int[]{1, 2, 3},
            new int[]{0, 1, 0}
        );
        assertEquals(0, HierarchyFilter.filter(input, id -> false).size());
    }

    @Test
    void singleRoot() {
        Hierarchy input = h(new int[]{42}, new int[]{0});
        assertSameShape(input, HierarchyFilter.filter(input, id -> id == 42));
        assertEquals(0, HierarchyFilter.filter(input, id -> false).size());
    }

    @Test
    void onlyRoots_independentOfEachOther() {
        Hierarchy input = h(
            new int[]{1, 2, 3, 4, 5},
            new int[]{0, 0, 0, 0, 0}
        );
        Hierarchy expected = h(new int[]{2, 4}, new int[]{0, 0});
        assertSameShape(expected, HierarchyFilter.filter(input, id -> id % 2 == 0));
    }

    @Test
    void rejectingRoot_dropsEntireTree() {
        Hierarchy input = h(
            new int[]{1, 2, 3, 4, 5},
            new int[]{0, 1, 2, 1, 0}
        );
        Hierarchy expected = h(new int[]{5}, new int[]{0});
        assertSameShape(expected, HierarchyFilter.filter(input, id -> id != 1));
    }

    @Test
    void rejectingIntermediateNode_dropsItsSubtreeOnly() {
        // Tree: 1 -> 2 -> {3, 4}; 1 -> 5
        // Reject 2 -> 3 and 4 disappear too, even though they pass.
        Hierarchy input = h(
            new int[]{1, 2, 3, 4, 5},
            new int[]{0, 1, 2, 2, 1}
        );
        Hierarchy expected = h(
            new int[]{1, 5},
            new int[]{0, 1}
        );
        assertSameShape(expected, HierarchyFilter.filter(input, id -> id != 2));
    }

    @Test
    void rejectingLeaf_doesNotAffectSiblings() {
        Hierarchy input = h(
            new int[]{1, 2, 3, 4},
            new int[]{0, 1, 1, 1}
        );
        Hierarchy expected = h(
            new int[]{1, 2, 4},
            new int[]{0, 1, 1}
        );
        assertSameShape(expected, HierarchyFilter.filter(input, id -> id != 3));
    }

    @Test
    void chainBrokenInMiddle() {
        // 1 -> 2 -> 3 -> 4 -> 5; reject 3.
        Hierarchy input = h(
            new int[]{1, 2, 3, 4, 5},
            new int[]{0, 1, 2, 3, 4}
        );
        Hierarchy expected = h(
            new int[]{1, 2},
            new int[]{0, 1}
        );
        assertSameShape(expected, HierarchyFilter.filter(input, id -> id != 3));
    }

    @Test
    void depthDropAcrossSubtrees() {
        // Forest: 1 -> 2 -> 3; 4 -> 5.
        // Reject 4. Cutoff (depth 0) must clear before we visit 5 (also depth 1 sibling? no, child of 4)
        // and we must NOT prune anything from tree rooted at 1.
        Hierarchy input = h(
            new int[]{1, 2, 3, 4, 5},
            new int[]{0, 1, 2, 0, 1}
        );
        Hierarchy expected = h(
            new int[]{1, 2, 3},
            new int[]{0, 1, 2}
        );
        assertSameShape(expected, HierarchyFilter.filter(input, id -> id != 4));
    }

    @Test
    void siblingAfterDeepSubtree_isNotPrunedByMistake() {
        // 1 -> 2 -> 3 -> 4; 1 -> 5. Reject 3.
        // 4 must be dropped (descendant of 3), 5 must survive (sibling of 2, not under 3).
        Hierarchy input = h(
            new int[]{1, 2, 3, 4, 5},
            new int[]{0, 1, 2, 3, 1}
        );
        Hierarchy expected = h(
            new int[]{1, 2, 5},
            new int[]{0, 1, 1}
        );
        assertSameShape(expected, HierarchyFilter.filter(input, id -> id != 3));
    }

    @Test
    void multipleRejectionsInSameTree() {
        // 1 -> {2 -> 3, 4 -> 5, 6}. Reject 2 and 4.
        Hierarchy input = h(
            new int[]{1, 2, 3, 4, 5, 6},
            new int[]{0, 1, 2, 1, 2, 1}
        );
        Hierarchy expected = h(
            new int[]{1, 6},
            new int[]{0, 1}
        );
        IntPredicate p = id -> id != 2 && id != 4;
        assertSameShape(expected, HierarchyFilter.filter(input, p));
    }

    @Test
    void predicateNotInvokedForNodesUnderRejectedAncestor() {
        // Predicate must be called once for the root, then never again because the root is rejected.
        int[] calls = {0};
        IntPredicate counting = id -> {
            calls[0]++;
            return false;
        };
        Hierarchy input = h(
            new int[]{1, 2, 3, 4},
            new int[]{0, 1, 2, 1}
        );
        Hierarchy result = HierarchyFilter.filter(input, counting);
        assertEquals(0, result.size());
        assertEquals(1, calls[0], "predicate should not be evaluated on descendants of a rejected ancestor");
    }

    @Test
    void resultDoesNotShareArraysWithInput() {
        // Sanity: even when nothing is filtered out, the returned hierarchy must own its arrays.
        int[] ids = {1, 2};
        int[] depths = {0, 1};
        ArrayBasedHierarchy input = new ArrayBasedHierarchy(ids, depths);
        Hierarchy result = HierarchyFilter.filter(input, id -> true);
        assertNotSame(input, result);
        assertSameShape(input, result);
    }

    @Test
    void formatStringMatchesSpec() {
        Hierarchy h = h(new int[]{1, 2, 5}, new int[]{0, 1, 1});
        assertEquals("[1:0, 2:1, 5:1]", h.formatString());
    }

    private static Hierarchy h(int[] ids, int[] depths) {
        return new ArrayBasedHierarchy(ids, depths);
    }

    private static void assertSameShape(Hierarchy expected, Hierarchy actual) {
        assertEquals(expected.formatString(), actual.formatString());
    }
}