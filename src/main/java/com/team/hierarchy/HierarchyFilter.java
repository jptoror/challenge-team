package com.team.hierarchy;

import java.util.function.IntPredicate;

/**
 * A node is present in the filtered hierarchy iff its node ID passes the predicate
 * and all of its ancestors pass it as well.
 *
 * <p>Assumptions:
 * <ul>
 *   <li>The input hierarchy honors the depth invariants documented on {@link Hierarchy}.
 *       Nothing is validated defensively.</li>
 *   <li>The predicate is pure. It is invoked at most once per node, and only for nodes
 *       whose ancestors have already passed - so a rejected ancestor short-circuits the
 *       whole subtree without further predicate calls.</li>
 *   <li>Output depths are preserved verbatim from the input. Filtering never re-parents
 *       survivors onto a closer ancestor; if a node's parent is dropped, the node is too.</li>
 * </ul>
 *
 * <p>Runs in O(n) time and uses O(k) extra memory, where k is the number of survivors.
 */
public final class HierarchyFilter {

    private HierarchyFilter() {}

    public static Hierarchy filter(Hierarchy hierarchy, IntPredicate nodeIdPredicate) {
        int n = hierarchy.size();
        if (n == 0) {
            return new ArrayBasedHierarchy(new int[0], new int[0]);
        }

        int[] outIds = new int[n];
        int[] outDepths = new int[n];
        int kept = 0;

        // When an ancestor fails the predicate, we drop everything deeper than its depth
        // until the traversal climbs back out. -1 means "not currently pruning".
        int pruneAbove = -1;

        for (int i = 0; i < n; i++) {
            int depth = hierarchy.depth(i);

            if (pruneAbove >= 0) {
                if (depth > pruneAbove) {
                    continue;
                }
                pruneAbove = -1;
            }

            int id = hierarchy.nodeId(i);
            if (nodeIdPredicate.test(id)) {
                outIds[kept] = id;
                outDepths[kept] = depth;
                kept++;
            } else {
                pruneAbove = depth;
            }
        }

        if (kept == n) {
            return new ArrayBasedHierarchy(outIds, outDepths);
        }
        int[] ids = new int[kept];
        int[] depths = new int[kept];
        System.arraycopy(outIds, 0, ids, 0, kept);
        System.arraycopy(outDepths, 0, depths, 0, kept);
        return new ArrayBasedHierarchy(ids, depths);
    }
}